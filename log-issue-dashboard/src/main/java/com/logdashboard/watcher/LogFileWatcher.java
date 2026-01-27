package com.logdashboard.watcher;

import com.logdashboard.config.DashboardConfig;
import com.logdashboard.config.ServerPath;
import com.logdashboard.model.LogIssue;
import com.logdashboard.parser.LogParser;
import com.logdashboard.util.EncodingDetector;
import com.logdashboard.util.IconvConverter;

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
 * Memory optimization: limits the number of tracked files and buffer sizes.
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
    private final Map<Path, String> fileIconvEncodings;  // Maps file path to iconv encoding name
    private final Map<Path, Boolean> fileUseIconv;    // Whether to use iconv for conversion
    private final ScheduledExecutorService scheduler;
    private final List<Pattern> filePatterns;
    
    // Track dynamically added server paths for polling
    private final List<ServerPath> dynamicServerPaths;
    
    // Memory limits
    private static final int MAX_TRACKED_FILES = 1000;
    private static final int MAX_READ_BUFFER_SIZE = 64 * 1024;  // 64KB max per read
    private static final int MAX_LINES_PER_READ = 10000;  // Limit lines processed per read
    
    private volatile boolean running;
    
    /**
     * Result of reading new lines from a file.
     * Contains both the lines read and the actual byte position reached.
     */
    private static class ReadResult {
        final List<String> lines;
        final long bytesRead;
        
        ReadResult(List<String> lines, long bytesRead) {
            this.lines = lines;
            this.bytesRead = bytesRead;
        }
    }
    
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
        this.fileIconvEncodings = new ConcurrentHashMap<>();
        this.fileUseIconv = new ConcurrentHashMap<>();
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
        // Check iconv availability at startup
        if (IconvConverter.isIconvAvailable()) {
            updateStatus("iconv command is available for encoding conversion");
        } else {
            updateStatus("iconv command not available, using Java charset handling");
        }
        
        // Scan legacy watch paths (no server name)
        if (config.getWatchPaths() != null) {
            for (String pathStr : config.getWatchPaths()) {
                scanPath(pathStr, null, StandardCharsets.UTF_8, null, false);
            }
        }
        
        // Scan server-based paths
        if (config.getServers() != null) {
            for (ServerPath server : config.getServers()) {
                String serverName = server.getServerName();
                String pathStr = server.getPath();
                String encodingInfo = server.getEncoding() != null ? 
                    " [" + server.getEncoding() + (server.isUseIconv() ? "/iconv" : "") + "]" : "";
                updateStatus("Scanning server: " + serverName + " -> " + pathStr + encodingInfo);
                scanPath(server);
            }
        }
    }
    
    /**
     * Scans a server path for log files.
     * Supports per-file encoding configuration via ServerPath.fileEncodings.
     */
    private void scanPath(ServerPath server) {
        String pathStr = server.getPath();
        String serverName = server.getServerName();
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
                            // Get per-file encoding (or use server default)
                            String fileName = file.getFileName().toString();
                            String fileEncoding = server.getEncodingForFile(fileName);
                            initializeFileWithEncoding(file, serverName, fileEncoding, server.isUseIconv());
                        }
                    }
                }
            } else if (Files.isRegularFile(watchPath)) {
                String fileName = watchPath.getFileName().toString();
                String fileEncoding = server.getEncodingForFile(fileName);
                initializeFileWithEncoding(watchPath, serverName, fileEncoding, server.isUseIconv());
            }
        } catch (IOException e) {
            updateStatus("Error scanning path: " + pathStr + " - " + e.getMessage());
        }
    }
    
    // Legacy method for backward compatibility
    private void scanPath(String pathStr, String serverName, Charset charset, 
                          String iconvEncoding, boolean useIconv) {
        ServerPath server = new ServerPath(serverName, pathStr, null, 
            iconvEncoding != null ? iconvEncoding : charset.displayName(), useIconv);
        scanPath(server);
    }
    
    /**
     * Initializes tracking for a file with a specific encoding.
     * Uses the correct conversion method based on encoding type:
     * - EBCDIC (IBM-1047, etc.): iconv -f IBM-1047 -t ISO8859-1
     * - UTF-8 or ISO8859-1: Direct read
     */
    private void initializeFileWithEncoding(Path file, String serverName, String encoding, boolean useIconv) {
        // Determine the iconv encoding and charset based on the encoding string
        String iconvEncoding = IconvConverter.normalizeToIconvEncoding(encoding);
        Charset charset;
        
        // For EBCDIC, we'll convert to ISO8859-1, so set charset accordingly
        if (IconvConverter.isEbcdicEncoding(encoding)) {
            charset = StandardCharsets.ISO_8859_1;  // Result will be ISO8859-1 after conversion
            useIconv = true;  // Force iconv for EBCDIC
        } else if ("ISO8859-1".equals(iconvEncoding) || "ISO-8859-1".equalsIgnoreCase(encoding)) {
            charset = StandardCharsets.ISO_8859_1;
            useIconv = false;  // Direct read for ISO8859-1
        } else {
            charset = StandardCharsets.UTF_8;
            useIconv = false;  // Direct read for UTF-8
        }
        
        initializeFile(file, serverName, charset, iconvEncoding, useIconv);
    }
    
    /**
     * Initializes tracking for a file (sets position to end of file).
     * If charset is UTF-8 (the default), attempts to auto-detect encoding.
     * Enforces maximum tracked files limit to prevent memory exhaustion.
     */
    private void initializeFile(Path file, String serverName, Charset charset, 
                                String iconvEncoding, boolean useIconv) {
        // Check if we've reached the maximum number of tracked files
        if (filePositions.size() >= MAX_TRACKED_FILES) {
            updateStatus("Warning: Maximum tracked files limit (" + MAX_TRACKED_FILES + ") reached. Skipping: " + file.getFileName());
            return;
        }
        
        try {
            // Auto-detect encoding if not explicitly configured (UTF-8 is the default)
            Charset effectiveCharset = charset;
            String effectiveIconvEncoding = iconvEncoding;
            boolean effectiveUseIconv = useIconv;
            boolean autoDetected = false;
            
            if (StandardCharsets.UTF_8.equals(charset) && iconvEncoding == null) {
                // Try to auto-detect encoding
                EncodingDetector.EncodingResult detection = EncodingDetector.detectEncodingWithDetails(file);
                if (detection.isEbcdic() && detection.confidence > 0.5) {
                    effectiveCharset = detection.charset;
                    effectiveIconvEncoding = IconvConverter.javaCharsetToIconvEncoding(effectiveCharset);
                    autoDetected = true;
                    updateStatus(String.format("Auto-detected EBCDIC encoding for %s (confidence: %.0f%%)",
                        file.getFileName(), detection.confidence * 100));
                }
            }
            
            long size = Files.size(file);
            // Skip counting lines for very large files to reduce CPU usage at startup
            int lineCount = 0;
            if (size < 10 * 1024 * 1024) {  // Only count lines for files < 10MB
                lineCount = countLines(file, effectiveCharset, effectiveIconvEncoding, effectiveUseIconv);
            }
            filePositions.put(file, size);
            fileLineNumbers.put(file, lineCount);
            if (serverName != null) {
                fileServerNames.put(file, serverName);
            }
            fileCharsets.put(file, effectiveCharset);
            if (effectiveIconvEncoding != null) {
                fileIconvEncodings.put(file, effectiveIconvEncoding);
            }
            fileUseIconv.put(file, effectiveUseIconv);
            
            String serverInfo = serverName != null ? " [" + serverName + "]" : "";
            String charsetInfo = "";
            if (!StandardCharsets.UTF_8.equals(effectiveCharset)) {
                String encodingName = effectiveUseIconv && effectiveIconvEncoding != null ? 
                    effectiveIconvEncoding + "/iconv" : effectiveCharset.displayName();
                charsetInfo = autoDetected ? 
                    " (auto-detected: " + encodingName + ")" :
                    " (" + encodingName + ")";
            }
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
                if (iconvEncoding != null) {
                    fileIconvEncodings.put(file, iconvEncoding);
                }
                fileUseIconv.put(file, useIconv);
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
        return countLines(file, charset, null, false);
    }
    
    /**
     * Counts the number of lines in a file.
     * Supports iconv-based conversion for better EBCDIC compatibility.
     */
    private int countLines(Path file, Charset charset, String iconvEncoding, boolean useIconv) 
            throws IOException {
        if (useIconv && iconvEncoding != null && IconvConverter.isIconvAvailable()) {
            // Use iconv for conversion
            String[] lines = IconvConverter.readEbcdicFileLines(file, iconvEncoding);
            return lines.length;
        }
        
        // Use Java's built-in charset handling
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
                pollPath(pathStr, null, StandardCharsets.UTF_8, null, false, currentFiles);
            }
        }
        
        // Poll server-based paths from initial config
        if (config.getServers() != null) {
            for (ServerPath server : config.getServers()) {
                pollPath(server.getPath(), server.getServerName(), server.getCharset(), 
                         server.getIconvEncoding(), server.isUseIconv(), currentFiles);
            }
        }
        
        // Poll dynamically added server paths (from hot reload)
        synchronized (dynamicServerPaths) {
            for (ServerPath server : dynamicServerPaths) {
                pollPath(server.getPath(), server.getServerName(), server.getCharset(),
                         server.getIconvEncoding(), server.isUseIconv(), currentFiles);
            }
        }
        
        // Check for new files
        for (Path file : currentFiles) {
            if (!filePositions.containsKey(file)) {
                String serverName = fileServerNames.get(file);
                Charset charset = fileCharsets.getOrDefault(file, StandardCharsets.UTF_8);
                String iconvEncoding = fileIconvEncodings.get(file);
                boolean useIconv = fileUseIconv.getOrDefault(file, false);
                initializeFile(file, serverName, charset, iconvEncoding, useIconv);
            }
        }
    }
    
    private void pollPath(String pathStr, String serverName, Charset charset, 
                          String iconvEncoding, boolean useIconv, Set<Path> currentFiles) {
        Path watchPath = Paths.get(pathStr);
        
        try {
            // Check if path exists
            if (!Files.exists(watchPath)) {
                // Only log this warning periodically (not every poll cycle)
                // to avoid flooding the logs
                String serverInfo = serverName != null ? " [" + serverName + "]" : "";
                System.err.println("Warning: Path does not exist: " + pathStr + serverInfo);
                return;
            }
            
            if (Files.isDirectory(watchPath)) {
                int matchingFiles = 0;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchPath)) {
                    for (Path file : stream) {
                        if (Files.isRegularFile(file) && matchesFilePattern(file)) {
                            matchingFiles++;
                            currentFiles.add(file);
                            if (serverName != null && !fileServerNames.containsKey(file)) {
                                fileServerNames.put(file, serverName);
                            }
                            if (!fileCharsets.containsKey(file)) {
                                fileCharsets.put(file, charset);
                            }
                            if (iconvEncoding != null && !fileIconvEncodings.containsKey(file)) {
                                fileIconvEncodings.put(file, iconvEncoding);
                            }
                            if (!fileUseIconv.containsKey(file)) {
                                fileUseIconv.put(file, useIconv);
                            }
                            checkFileForChanges(file);
                        }
                    }
                }
                // Log if directory exists but no matching files found (for debugging)
                if (matchingFiles == 0 && !pathStr.equals(lastEmptyPathWarned)) {
                    String serverInfo = serverName != null ? " [" + serverName + "]" : "";
                    updateStatus("No matching files in: " + pathStr + serverInfo + " (patterns: " + filePatterns + ")");
                    lastEmptyPathWarned = pathStr;
                }
            } else if (Files.isRegularFile(watchPath)) {
                if (matchesFilePattern(watchPath)) {
                    currentFiles.add(watchPath);
                    if (serverName != null && !fileServerNames.containsKey(watchPath)) {
                        fileServerNames.put(watchPath, serverName);
                    }
                    if (!fileCharsets.containsKey(watchPath)) {
                        fileCharsets.put(watchPath, charset);
                    }
                    if (iconvEncoding != null && !fileIconvEncodings.containsKey(watchPath)) {
                        fileIconvEncodings.put(watchPath, iconvEncoding);
                    }
                    if (!fileUseIconv.containsKey(watchPath)) {
                        fileUseIconv.put(watchPath, useIconv);
                    }
                    checkFileForChanges(watchPath);
                } else {
                    String serverInfo = serverName != null ? " [" + serverName + "]" : "";
                    System.err.println("Warning: File does not match patterns: " + watchPath.getFileName() + serverInfo);
                }
            }
        } catch (IOException e) {
            // Log error to both stderr and status callback
            String serverInfo = serverName != null ? " [" + serverName + "]" : "";
            String errorMsg = "Error polling: " + pathStr + serverInfo + " - " + e.getMessage();
            System.err.println(errorMsg);
            updateStatus(errorMsg);
        }
    }
    
    // Track last empty path warned to avoid repetitive warnings
    private volatile String lastEmptyPathWarned = null;
    
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
            String iconvEncoding = fileIconvEncodings.get(file);
            boolean useIconv = fileUseIconv.getOrDefault(file, false);
            
            if (currentSize > lastPosition) {
                // File has grown - read new content
                long bytesToRead = currentSize - lastPosition;
                String serverInfo = serverName != null ? " [" + serverName + "]" : "";
                
                // Debug: Log when new content is detected
                if (verboseLogging) {
                    updateStatus("New content detected in " + file.getFileName() + serverInfo + 
                        " (" + bytesToRead + " bytes, pos " + lastPosition + " -> " + currentSize + ")");
                }
                
                ReadResult result = readNewLinesWithPosition(file, lastPosition, charset, iconvEncoding, useIconv);
                
                if (!result.lines.isEmpty()) {
                    // Debug: Log number of lines read
                    if (verboseLogging) {
                        updateStatus("Read " + result.lines.size() + " lines from " + file.getFileName() + serverInfo);
                        // Show first few lines for debugging
                        int previewLines = Math.min(3, result.lines.size());
                        for (int i = 0; i < previewLines; i++) {
                            String line = result.lines.get(i);
                            if (line.length() > 100) {
                                line = line.substring(0, 100) + "...";
                            }
                            updateStatus("  Line " + (lastLineNumber + 1 + i) + ": " + line);
                        }
                    }
                    
                    List<LogIssue> issues = parser.parseLines(
                        serverName,
                        file.getFileName().toString(),
                        result.lines,
                        lastLineNumber + 1
                    );
                    
                    // Debug: Log issues found
                    if (verboseLogging && issues.isEmpty()) {
                        updateStatus("No issues detected in " + result.lines.size() + " lines from " + file.getFileName() + serverInfo);
                    } else if (!issues.isEmpty()) {
                        updateStatus("Detected " + issues.size() + " issue(s) in " + file.getFileName() + serverInfo);
                    }
                    
                    for (LogIssue issue : issues) {
                        issueCallback.accept(issue);
                    }
                    
                    fileLineNumbers.put(file, lastLineNumber + result.lines.size());
                } else if (verboseLogging && result.bytesRead > 0) {
                    updateStatus("Read " + result.bytesRead + " bytes but no complete lines from " + file.getFileName() + serverInfo);
                }
                
                // Update position to actual bytes read, not currentSize
                // This prevents data loss if line limit was reached before end of file
                filePositions.put(file, lastPosition + result.bytesRead);
            } else if (currentSize < lastPosition) {
                // File was truncated or rotated - reset tracking
                String serverInfo = serverName != null ? " [" + serverName + "]" : "";
                updateStatus("File rotated: " + file.getFileName() + serverInfo);
                filePositions.put(file, currentSize);
                try {
                    fileLineNumbers.put(file, countLines(file, charset, iconvEncoding, useIconv));
                } catch (IOException countError) {
                    // If we can't count lines, just reset to 0
                    fileLineNumbers.put(file, 0);
                }
            }
        } catch (IOException e) {
            System.err.println("Error checking file: " + file + " - " + e.getMessage());
        }
    }
    
    // Verbose logging flag - can be enabled for debugging
    private volatile boolean verboseLogging = false;
    
    /**
     * Enables or disables verbose logging for debugging.
     */
    public void setVerboseLogging(boolean enabled) {
        this.verboseLogging = enabled;
        updateStatus("Verbose logging " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Returns whether verbose logging is enabled.
     */
    public boolean isVerboseLogging() {
        return verboseLogging;
    }
    
    /**
     * Reads new lines from a file starting from a given position.
     * Supports various encodings including EBCDIC.
     */
    private List<String> readNewLines(Path file, long startPosition, Charset charset) throws IOException {
        return readNewLinesWithPosition(file, startPosition, charset, null, false).lines;
    }
    
    /**
     * Reads new lines from a file starting from a given position.
     * Supports iconv-based conversion for better EBCDIC compatibility.
     * Memory optimization: limits buffer size and number of lines read.
     * 
     * Returns a ReadResult containing both the lines and actual bytes read,
     * to prevent data loss when line limit is reached before end of file.
     */
    private ReadResult readNewLinesWithPosition(Path file, long startPosition, Charset charset,
                                      String iconvEncoding, boolean useIconv) throws IOException {
        List<String> lines = new ArrayList<>();
        long totalBytesRead = 0;
        
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(startPosition);
            
            // Read remaining content
            long remaining = channel.size() - startPosition;
            if (remaining <= 0) {
                return new ReadResult(lines, 0);
            }
            
            // Use a reasonable buffer size (capped at MAX_READ_BUFFER_SIZE)
            int bufferSize = (int) Math.min(remaining, MAX_READ_BUFFER_SIZE);
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            StringBuilder currentLine = new StringBuilder(256);  // Pre-size for typical line length
            int bytesInCurrentLine = 0;  // Track bytes for the incomplete line
            
            int bytesReadThisChunk;
            while ((bytesReadThisChunk = channel.read(buffer)) > 0 && lines.size() < MAX_LINES_PER_READ) {
                buffer.flip();
                
                // Decode bytes using the specified charset or iconv
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                
                String content;
                
                // Determine conversion method based on encoding type:
                // - EBCDIC (IBM-1047, etc.): Use iconv -f IBM-1047 -t ISO8859-1
                // - UTF-8 or ISO8859-1: Direct read (no conversion needed)
                boolean isEbcdic = IconvConverter.isEbcdicEncoding(iconvEncoding);
                
                if (isEbcdic && IconvConverter.isIconvAvailable()) {
                    // EBCDIC encoding: Convert to ISO8859-1
                    // Command: iconv -f IBM-1047 -t ISO8859-1
                    try {
                        content = IconvConverter.convertEbcdicToReadable(bytes, iconvEncoding);
                    } catch (IOException e) {
                        // Fall back to Java charset if iconv fails
                        content = new String(bytes, charset);
                    }
                } else if (StandardCharsets.ISO_8859_1.equals(charset) || "ISO8859-1".equals(iconvEncoding)) {
                    // ISO8859-1: Direct read
                    content = new String(bytes, StandardCharsets.ISO_8859_1);
                } else {
                    // UTF-8 or other: Direct read with specified charset
                    content = new String(bytes, charset);
                }
                
                // Split into lines with line count limit check
                int lastLineEndByte = 0;  // Track position of last complete line
                for (int i = 0; i < content.length(); i++) {
                    if (lines.size() >= MAX_LINES_PER_READ) {
                        // We've hit the line limit - stop processing but account for bytes read
                        // Only count bytes up to the last complete line
                        totalBytesRead += lastLineEndByte;
                        return new ReadResult(lines, totalBytesRead);
                    }
                    
                    char c = content.charAt(i);
                    if (c == '\n') {
                        lines.add(currentLine.toString());
                        currentLine.setLength(0);
                        bytesInCurrentLine = 0;
                        lastLineEndByte = i + 1;  // +1 to include the newline
                    } else if (c == '\r') {
                        // Handle \r\n or standalone \r
                        if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                            // Skip, will be handled by \n
                        } else {
                            lines.add(currentLine.toString());
                            currentLine.setLength(0);
                            bytesInCurrentLine = 0;
                            lastLineEndByte = i + 1;
                        }
                    } else {
                        // Limit line length to prevent memory issues with very long lines
                        if (currentLine.length() < 10000) {
                            currentLine.append(c);
                        }
                        bytesInCurrentLine++;
                    }
                }
                
                // If we processed all content in this chunk, add to total bytes
                totalBytesRead += bytesReadThisChunk;
                buffer.clear();
            }
            
            // Add any remaining content as the last line (if under limit)
            if (currentLine.length() > 0 && lines.size() < MAX_LINES_PER_READ) {
                lines.add(currentLine.toString());
            }
        }
        
        return new ReadResult(lines, totalBytesRead);
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
        fileIconvEncodings.clear();
        fileUseIconv.clear();
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
            String iconvEncoding = server.getIconvEncoding();
            boolean useIconv = server.isUseIconv();
            
            if (pathStr == null || pathStr.isEmpty()) {
                continue;
            }
            
            String serverInfo = serverName != null ? " [" + serverName + "]" : "";
            String encodingInfo = server.getEncoding() != null ? 
                " (" + server.getEncoding() + (useIconv ? "/iconv" : "") + ")" : "";
            updateStatus("Adding new server path: " + pathStr + serverInfo + encodingInfo);
            
            // Add to dynamic server paths list so pollFiles() will monitor it
            dynamicServerPaths.add(server);
            addedCount++;
            
            // Scan the new path for initial file discovery
            scanPath(pathStr, serverName, charset, iconvEncoding, useIconv);
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
        addServerPath(serverName, path, null, false);
    }
    
    /**
     * Adds a single server path to watch dynamically with custom encoding.
     * 
     * @param serverName The server name (can be null for legacy paths)
     * @param path The path to watch
     * @param encoding The character encoding (e.g., "UTF-8", "EBCDIC", "Cp1047", "IBM-1047")
     */
    public void addServerPath(String serverName, String path, String encoding) {
        addServerPath(serverName, path, encoding, false);
    }
    
    /**
     * Adds a single server path to watch dynamically with custom encoding and iconv option.
     * 
     * @param serverName The server name (can be null for legacy paths)
     * @param path The path to watch
     * @param encoding The character encoding (e.g., "UTF-8", "EBCDIC", "Cp1047", "IBM-1047")
     * @param useIconv Whether to use external iconv command for encoding conversion
     */
    public void addServerPath(String serverName, String path, String encoding, boolean useIconv) {
        if (path == null || path.isEmpty()) {
            return;
        }
        
        ServerPath server = new ServerPath(serverName, path, null, encoding, useIconv);
        String serverInfo = serverName != null ? " [" + serverName + "]" : "";
        String encodingInfo = encoding != null ? 
            " (" + encoding + (useIconv ? "/iconv" : "") + ")" : "";
        updateStatus("Adding new server path: " + path + serverInfo + encodingInfo);
        
        // Add to dynamic server paths list so pollFiles() will monitor it
        dynamicServerPaths.add(server);
        
        // Scan the new path for initial file discovery
        scanPath(path, serverName, server.getCharset(), server.getIconvEncoding(), useIconv);
        
        updateStatus("Now watching " + getTotalWatchPaths() + " path(s)");
    }
    
    /**
     * Returns true if the watcher is currently running.
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Returns diagnostic information about the watcher status.
     * Useful for troubleshooting.
     */
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("running", running);
        diagnostics.put("trackedFilesCount", filePositions.size());
        diagnostics.put("maxTrackedFiles", MAX_TRACKED_FILES);
        diagnostics.put("pollingIntervalSeconds", config.getPollingIntervalSeconds());
        
        // File patterns
        List<String> patterns = new ArrayList<>();
        for (Pattern p : filePatterns) {
            patterns.add(p.pattern());
        }
        diagnostics.put("filePatterns", patterns);
        
        // Configured paths
        List<Map<String, Object>> configuredPaths = new ArrayList<>();
        if (config.getWatchPaths() != null) {
            for (String path : config.getWatchPaths()) {
                Map<String, Object> pathInfo = new LinkedHashMap<>();
                pathInfo.put("path", path);
                pathInfo.put("type", "legacy");
                pathInfo.put("exists", Files.exists(Paths.get(path)));
                configuredPaths.add(pathInfo);
            }
        }
        if (config.getServers() != null) {
            for (ServerPath server : config.getServers()) {
                Map<String, Object> pathInfo = new LinkedHashMap<>();
                pathInfo.put("path", server.getPath());
                pathInfo.put("serverName", server.getServerName());
                pathInfo.put("type", "server");
                pathInfo.put("exists", Files.exists(Paths.get(server.getPath())));
                pathInfo.put("encoding", server.getEncoding());
                configuredPaths.add(pathInfo);
            }
        }
        for (ServerPath server : dynamicServerPaths) {
            Map<String, Object> pathInfo = new LinkedHashMap<>();
            pathInfo.put("path", server.getPath());
            pathInfo.put("serverName", server.getServerName());
            pathInfo.put("type", "dynamic");
            pathInfo.put("exists", Files.exists(Paths.get(server.getPath())));
            pathInfo.put("encoding", server.getEncoding());
            configuredPaths.add(pathInfo);
        }
        diagnostics.put("configuredPaths", configuredPaths);
        
        // Tracked files
        List<Map<String, Object>> trackedFilesList = new ArrayList<>();
        for (Path file : filePositions.keySet()) {
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("path", file.toString());
            fileInfo.put("fileName", file.getFileName().toString());
            fileInfo.put("serverName", fileServerNames.get(file));
            fileInfo.put("position", filePositions.get(file));
            fileInfo.put("lineNumber", fileLineNumbers.get(file));
            fileInfo.put("charset", fileCharsets.get(file) != null ? fileCharsets.get(file).displayName() : "UTF-8");
            try {
                fileInfo.put("currentSize", Files.size(file));
                fileInfo.put("exists", true);
            } catch (IOException e) {
                fileInfo.put("exists", false);
            }
            trackedFilesList.add(fileInfo);
        }
        diagnostics.put("trackedFiles", trackedFilesList);
        
        return diagnostics;
    }
}
