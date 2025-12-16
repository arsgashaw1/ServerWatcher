package com.logdashboard.watcher;

import com.logdashboard.config.DashboardConfig;
import com.logdashboard.model.LogIssue;
import com.logdashboard.parser.LogParser;

import java.io.*;
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
    private final ScheduledExecutorService scheduler;
    private final List<Pattern> filePatterns;
    
    private volatile boolean running;
    
    public LogFileWatcher(DashboardConfig config, Consumer<LogIssue> issueCallback, 
                          Consumer<String> statusCallback) {
        this.config = config;
        this.parser = new LogParser(config);
        this.issueCallback = issueCallback;
        this.statusCallback = statusCallback;
        this.filePositions = new ConcurrentHashMap<>();
        this.fileLineNumbers = new ConcurrentHashMap<>();
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
        
        updateStatus("Watching " + config.getWatchPaths().size() + " path(s)");
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
        for (String pathStr : config.getWatchPaths()) {
            Path watchPath = Paths.get(pathStr);
            
            if (!Files.exists(watchPath)) {
                updateStatus("Warning: Watch path does not exist: " + pathStr);
                continue;
            }
            
            try {
                if (Files.isDirectory(watchPath)) {
                    // Scan directory for matching files
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchPath)) {
                        for (Path file : stream) {
                            if (Files.isRegularFile(file) && matchesFilePattern(file)) {
                                initializeFile(file);
                            }
                        }
                    }
                } else if (Files.isRegularFile(watchPath)) {
                    initializeFile(watchPath);
                }
            } catch (IOException e) {
                updateStatus("Error scanning path: " + pathStr + " - " + e.getMessage());
            }
        }
    }
    
    /**
     * Initializes tracking for a file (sets position to end of file).
     */
    private void initializeFile(Path file) {
        try {
            long size = Files.size(file);
            int lineCount = countLines(file);
            filePositions.put(file, size);
            fileLineNumbers.put(file, lineCount);
            updateStatus("Tracking: " + file.getFileName());
        } catch (IOException e) {
            updateStatus("Error initializing file: " + file + " - " + e.getMessage());
        }
    }
    
    /**
     * Counts the number of lines in a file.
     */
    private int countLines(Path file) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
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
        
        for (String pathStr : config.getWatchPaths()) {
            Path watchPath = Paths.get(pathStr);
            
            try {
                if (Files.isDirectory(watchPath)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchPath)) {
                        for (Path file : stream) {
                            if (Files.isRegularFile(file) && matchesFilePattern(file)) {
                                currentFiles.add(file);
                                checkFileForChanges(file);
                            }
                        }
                    }
                } else if (Files.isRegularFile(watchPath) && matchesFilePattern(watchPath)) {
                    currentFiles.add(watchPath);
                    checkFileForChanges(watchPath);
                }
            } catch (IOException e) {
                // Log error but continue
                System.err.println("Error polling: " + pathStr + " - " + e.getMessage());
            }
        }
        
        // Check for new files
        for (Path file : currentFiles) {
            if (!filePositions.containsKey(file)) {
                initializeFile(file);
            }
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
            
            if (currentSize > lastPosition) {
                // File has grown - read new content
                List<String> newLines = readNewLines(file, lastPosition);
                
                if (!newLines.isEmpty()) {
                    List<LogIssue> issues = parser.parseLines(
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
                updateStatus("File rotated: " + file.getFileName());
                filePositions.put(file, currentSize);
                fileLineNumbers.put(file, countLines(file));
            }
        } catch (IOException e) {
            System.err.println("Error checking file: " + file + " - " + e.getMessage());
        }
    }
    
    /**
     * Reads new lines from a file starting from a given position.
     */
    private List<String> readNewLines(Path file, long startPosition) throws IOException {
        List<String> lines = new ArrayList<>();
        
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(startPosition);
            String line;
            while ((line = raf.readLine()) != null) {
                // Handle encoding (readLine returns ISO-8859-1)
                lines.add(new String(line.getBytes("ISO-8859-1"), "UTF-8"));
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
     * Forces a rescan of all watched directories.
     */
    public void rescan() {
        filePositions.clear();
        fileLineNumbers.clear();
        initialScan();
    }
}
