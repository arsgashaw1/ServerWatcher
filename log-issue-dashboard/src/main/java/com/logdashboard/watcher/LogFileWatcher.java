package com.logdashboard.watcher;

import com.logdashboard.config.DashboardConfig;
import com.logdashboard.config.ServerPath;
import com.logdashboard.model.LogIssue;
import com.logdashboard.parser.LogParser;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Watches specified directories for log file changes and detects issues.
 */
public class LogFileWatcher {
    
    private final DashboardConfig config;
    private final LogParser parser;
    private final Consumer<LogIssue> issueCallback;
    private final Consumer<String> statusCallback;
    
    private final Map<Path, Long> filePositions;
    private final Map<Path, Integer> fileLineNumbers;
    private final Map<Path, String> fileServerNames;  // Maps file path to server name
    private final Map<Path, Charset> fileCharsets;    // Maps file path to charset (for EBCDIC support)
    private final ScheduledExecutorService scheduler;
    private final List<Pattern> filePatterns;
    
    // Track dynamically added server paths for polling
    private final List<ServerPath> dynamicServerPaths;
    
    private volatile boolean running;
    
    public LogFileWatcher(DashboardConfig config, Consumer<LogIssue> issueCallback, 
                          Consumer<String> statusCallback) {
        this.config = config;
        this.parser = new LogParser(config);
        this.issueCallback = issueCallback;
        this.statusCallback = statusCallback;
        this.filePositions = new ConcurrentHashMap<>();
        this.fileLineNumbers = new ConcurrentHashMap<>();
        this.fileServerNames = new ConcurrentHashMap<>();
        this.fileCharsets = new ConcurrentHashMap<>();
        this.dynamicServerPaths = Collections.synchronizedList(new ArrayList<>());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LogFileWatcher");
            t.setDaemon(true);
            return t;
        });
        this.filePatterns = compileFilePatterns(config.getFilePatterns());
        this.running = false;
    }
    
    private List<Pattern> compileFilePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            // Convert glob pattern to regex
            String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
            compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return compiled;
    }
    
    /**
     * Starts watching the configured directories for log file changes.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        
        // Initial scan of existing files
        updateStatus("Starting log file watcher...");
        initialScan();
        
        // Schedule periodic polling
        scheduler.scheduleAtFixedRate(
            this::pollFiles,
            config.getPollingIntervalSeconds(),
            config.getPollingIntervalSeconds(),
            TimeUnit.SECONDS
        );
        
        int totalPaths = getTotalWatchPaths();
        updateStatus("Watching " + totalPaths + " path(s)");
    }
    
    private int getTotalWatchPaths() {
        int count = 0;
        if (config.getWatchPaths() != null) {
            count += config.getWatchPaths().size();
        }
        if (config.getServers() != null) {
            count += config.getServers().size();
        }
        // Include dynamically added server paths
        count += dynamicServerPaths.size();
        return count;
    }
    
    /**
     * Stops the file watcher.
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        updateStatus("Watcher stopped");
    }
    
    /**
     * Performs initial scan of all watched directories.
     */
    private void initialScan() {
        // Scan legacy watch paths (no server name)
        if (config.getWatchPaths() != null) {
            for (String pathStr : config.getWatchPaths()) {
                scanPath(pathStr, null, StandardCharsets.UTF_8);
            }
        }
        
        // Scan server-based paths
        if (config.getServers() != null) {
            for (ServerPath server : config.getServers()) {
                String serverName = server.getServerName();
                String pathStr = server.getPath();
                Charset charset = server.getCharset();
                String encodingInfo = server.getEncoding() != null ? 
                    " [" + server.getEncoding() + "]" : "";
                updateStatus("Scanning server: " + serverName + " -> " + pathStr + encodingInfo);
                scanPath(pathStr, serverName, charset);
            }
        }
    }
    
    private void scanPath(String pathStr, String serverName, Charset charset) {
        Path watchPath = Paths.get(pathStr);
        
        if (!Files.exists(watchPath)) {
            String serverInfo = serverName != null ? " [" + serverName + "]" : "";
            updateStatus("Warning: Watch path does not exist: " + pathStr + serverInfo);
            return;
        }
        
        try {
            if (Files.isDirectory(watchPath)) {
                // Scan directory for matching files
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchPath)) {
                    for (Path file : stream) {
                        if (Files.isRegularFile(file) && matchesFilePattern(file)) {
                            initializeFile(file, serverName, charset);
                        }
                    }
                }
            } else if (Files.isRegularFile(watchPath)) {
                initializeFile(watchPath, serverName, charset);
            }
        } catch (IOException e) {
            updateStatus("Error scanning path: " + pathStr + " - " + e.getMessage());
        }
    }
    
    /**
     * Initializes tracking for a file (sets position to end of file).
     */
    private void initializeFile(Path file, String serverName, Charset charset) {
        try {
            long size = Files.size(file);
            int lineCount = countLines(file, charset);
            filePositions.put(file, size);
            fileLineNumbers.put(file, lineCount);
            if (serverName != null) {
                fileServerNames.put(file, serverName);
            }
            fileCharsets.put(file, charset);
            String serverInfo = serverName != null ? " [" + serverName + "]" : "";
            String charsetInfo = !StandardCharsets.UTF_8.equals(charset) ? 
                " (" + charset.displayName() + ")" : "";
            updateStatus("Tracking: " + file.getFileName() + serverInfo + charsetInfo);
        } catch (IOException e) {
            // If we can't read the file properly (e.g., encoding issues), 
            // still track it starting from the current position
            try {
                long size = Files.size(file);
                filePositions.put(file, size);
                fileLineNumbers.put(file, 0);  // Start from line 0 if we can't count
                if (serverName != null) {
                    fileServerNames.put(file, serverName);
                }
                fileCharsets.put(file, charset);
                String serverInfo = serverName != null ? " [" + serverName + "]" : "";
                updateStatus("Tracking: " + file.getFileName() + serverInfo);
            } catch (IOException ex) {
                // Only log error if we truly can't access the file
                updateStatus("Error initializing file: " + file + " - " + ex.getMessage());
            }
        }
    }
    
    /**
     * Counts the number of lines in a file.
     * Uses the specified charset to handle various encodings including EBCDIC.
     */
    private int countLines(Path file, Charset charset) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Polls all watched files for changes.
     */
    private void pollFiles() {
        if (!running) {
            return;
        }
        
        Set<Path> currentFiles = new HashSet<>();
        
        // Poll legacy watch paths
        if (config.getWatchPaths() != null) {
            for (String pathStr : config.getWatchPaths()) {
                pollPath(pathStr, null, StandardCharsets.UTF_8, currentFiles);
            }
        }
        
        // Poll server-based paths from initial config
        if (config.getServers() != null) {
            for (ServerPath server : config.getServers()) {
                pollPath(server.getPath(), server.getServerName(), server.getCharset(), currentFiles);
            }
        }
        
        // Poll dynamically added server paths (from hot reload)
        synchronized (dynamicServerPaths) {
            for (ServerPath server : dynamicServerPaths) {
                pollPath(server.getPath(), server.getServerName(), server.getCharset(), currentFiles);
            }
        }
        
        // Check for new files
        for (Path file : currentFiles) {
            if (!filePositions.containsKey(file)) {
                String serverName = fileServerNames.get(file);
                Charset charset = fileCharsets.getOrDefault(file, StandardCharsets.UTF_8);
                initializeFile(file, serverName, charset);
            }
        }
    }
    
    private void pollPath(String pathStr, String serverName, Charset charset, Set<Path> currentFiles) {
        Path watchPath = Paths.get(pathStr);
        
        try {
            if (Files.isDirectory(watchPath)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchPath)) {
                    for (Path file : stream) {
                        if (Files.isRegularFile(file) && matchesFilePattern(file)) {
                            currentFiles.add(file);
                            if (serverName != null && !fileServerNames.containsKey(file)) {
                                fileServerNames.put(file, serverName);
                            }
                            if (!fileCharsets.containsKey(file)) {
                                fileCharsets.put(file, charset);
                            }
                            checkFileForChanges(file);
                        }
                    }
                }
            } else if (Files.isRegularFile(watchPath) && matchesFilePattern(watchPath)) {
                currentFiles.add(watchPath);
                if (serverName != null && !fileServerNames.containsKey(watchPath)) {
                    fileServerNames.put(watchPath, serverName);
                }
                if (!fileCharsets.containsKey(watchPath)) {
                    fileCharsets.put(watchPath, charset);
                }
                checkFileForChanges(watchPath);
            }
        } catch (IOException e) {
            // Log error but continue
            System.err.println("Error polling: " + pathStr + " - " + e.getMessage());
        }
    }
    
    /**
     * Checks a specific file for new content.
     */
    private void checkFileForChanges(Path file) {
        try {
            long currentSize = Files.size(file);
            long lastPosition = filePositions.getOrDefault(file, 0L);
            int lastLineNumber = fileLineNumbers.getOrDefault(file, 0);
            String serverName = fileServerNames.get(file);
            Charset charset = fileCharsets.getOrDefault(file, StandardCharsets.UTF_8);
            
            if (currentSize > lastPosition) {
                // File has grown - read new content
                List<String> newLines = readNewLines(file, lastPosition, charset);
                
                if (!newLines.isEmpty()) {
                    List<LogIssue> issues = parser.parseLines(
                        serverName,
                        file.getFileName().toString(),
                        newLines,
                        lastLineNumber + 1
                    );
                    
                    for (LogIssue issue : issues) {
                        issueCallback.accept(issue);
                    }
                    
                    fileLineNumbers.put(file, lastLineNumber + newLines.size());
                }
                
                filePositions.put(file, currentSize);
            } else if (currentSize < lastPosition) {
                // File was truncated or rotated - reset tracking
                String serverInfo = serverName != null ? " [" + serverName + "]" : "";
                updateStatus("File rotated: " + file.getFileName() + serverInfo);
                filePositions.put(file, currentSize);
                try {
                    fileLineNumbers.put(file, countLines(file, charset));
                } catch (IOException countError) {
                    // If we can't count lines, just reset to 0
                    fileLineNumbers.put(file, 0);
                }
            }
        } catch (IOException e) {
            System.err.println("Error checking file: " + file + " - " + e.getMessage());
        }
    }
    
    /**
     * Reads new lines from a file starting from a given position.
     * Supports various encodings including EBCDIC.
     */
    private List<String> readNewLines(Path file, long startPosition, Charset charset) throws IOException {
        List<String> lines = new ArrayList<>();
        
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(startPosition);
            
            // Read remaining content
            long remaining = channel.size() - startPosition;
            if (remaining <= 0) {
                return lines;
            }
            
            // Use a reasonable buffer size
            int bufferSize = (int) Math.min(remaining, 64 * 1024); // Max 64KB chunks
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            StringBuilder currentLine = new StringBuilder();
            
            while (channel.read(buffer) > 0) {
                buffer.flip();
                
                // Decode bytes using the specified charset
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                String content = new String(bytes, charset);
                
                // Split into lines
                for (int i = 0; i < content.length(); i++) {
                    char c = content.charAt(i);
                    if (c == '\n') {
                        lines.add(currentLine.toString());
                        currentLine.setLength(0);
                    } else if (c == '\r') {
                        // Handle \r\n or standalone \r
                        if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                            // Skip, will be handled by \n
                        } else {
                            lines.add(currentLine.toString());
                            currentLine.setLength(0);
                        }
                    } else {
                        currentLine.append(c);
                    }
                }
                
                buffer.clear();
            }
            
            // Add any remaining content as the last line
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }
        }
        
        return lines;
    }
    
    /**
     * Checks if a file matches the configured file patterns.
     */
    private boolean matchesFilePattern(Path file) {
        String fileName = file.getFileName().toString();
        for (Pattern pattern : filePatterns) {
            if (pattern.matcher(fileName).matches()) {
                return true;
            }
        }
        return false;
    }
    
    private void updateStatus(String status) {
        if (statusCallback != null) {
            statusCallback.accept(status);
        }
    }
    
    /**
     * Returns the list of currently tracked files.
     */
    public Set<Path> getTrackedFiles() {
        return new HashSet<>(filePositions.keySet());
    }
    
    /**
     * Returns the server name for a tracked file.
     */
    public String getServerName(Path file) {
        return fileServerNames.get(file);
    }
    
    /**
     * Forces a rescan of all watched directories.
     */
    public void rescan() {
        filePositions.clear();
        fileLineNumbers.clear();
        fileServerNames.clear();
        fileCharsets.clear();
        initialScan();
    }
    
    /**
     * Adds new server paths to watch dynamically.
     * This is called when the configuration file is updated with new servers.
     * 
     * @param newServers List of new ServerPath objects to watch
     */
    public void addServerPaths(List<ServerPath> newServers) {
        if (newServers == null || newServers.isEmpty()) {
            return;
        }
        
        int addedCount = 0;
        
        for (ServerPath server : newServers) {
            String serverName = server.getServerName();
            String pathStr = server.getPath();
            Charset charset = server.getCharset();
            
            if (pathStr == null || pathStr.isEmpty()) {
                continue;
            }
            
            String serverInfo = serverName != null ? " [" + serverName + "]" : "";
            String encodingInfo = server.getEncoding() != null ? " (" + server.getEncoding() + ")" : "";
            updateStatus("Adding new server path: " + pathStr + serverInfo + encodingInfo);
            
            // Add to dynamic server paths list so pollFiles() will monitor it
            dynamicServerPaths.add(server);
            addedCount++;
            
            // Scan the new path for initial file discovery
            scanPath(pathStr, serverName, charset);
        }
        
        if (addedCount > 0) {
            updateStatus("Now watching " + getTotalWatchPaths() + " path(s)");
        }
    }
    
    /**
     * Adds a single server path to watch dynamically.
     * 
     * @param serverName The server name (can be null for legacy paths)
     * @param path The path to watch
     */
    public void addServerPath(String serverName, String path) {
        addServerPath(serverName, path, null);
    }
    
    /**
     * Adds a single server path to watch dynamically with custom encoding.
     * 
     * @param serverName The server name (can be null for legacy paths)
     * @param path The path to watch
     * @param encoding The character encoding (e.g., "UTF-8", "EBCDIC", "Cp1047")
     */
    public void addServerPath(String serverName, String path, String encoding) {
        if (path == null || path.isEmpty()) {
            return;
        }
        
        ServerPath server = new ServerPath(serverName, path, null, encoding);
        String serverInfo = serverName != null ? " [" + serverName + "]" : "";
        String encodingInfo = encoding != null ? " (" + encoding + ")" : "";
        updateStatus("Adding new server path: " + path + serverInfo + encodingInfo);
        
        // Add to dynamic server paths list so pollFiles() will monitor it
        dynamicServerPaths.add(server);
        
        // Scan the new path for initial file discovery
        scanPath(path, serverName, server.getCharset());
        
        updateStatus("Now watching " + getTotalWatchPaths() + " path(s)");
    }
}
