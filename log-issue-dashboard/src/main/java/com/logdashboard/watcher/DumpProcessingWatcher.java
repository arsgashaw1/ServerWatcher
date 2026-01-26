package com.logdashboard.watcher;

import com.logdashboard.model.DumpFileTracking;
import com.logdashboard.model.DumpProcessConfig;
import com.logdashboard.store.DumpProcessingStore;

import java.io.File;
import java.io.FilenameFilter;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Watches dump directories for .mdb files and triggers extraction when files
 * have been sitting for longer than the configured threshold.
 */
public class DumpProcessingWatcher {
    
    // Default polling interval: 30 seconds
    private static final int DEFAULT_POLLING_INTERVAL_SECONDS = 30;
    
    // Default cleanup interval: 24 hours (clean up old records)
    private static final int CLEANUP_INTERVAL_HOURS = 24;
    
    // Maximum retries for failed files
    private static final int MAX_RETRIES = 3;
    
    // File extension to watch for
    private static final String MDB_EXTENSION = ".mdb";
    
    private final DumpProcessingStore store;
    private final DumpScriptExecutor executor;
    private final Consumer<String> statusCallback;
    
    private final ScheduledExecutorService scheduler;
    private final ExecutorService processingExecutor;
    
    private final int pollingIntervalSeconds;
    private volatile boolean running;
    
    // Track files currently being processed to avoid duplicate processing
    private final Set<String> processingFiles;
    
    /**
     * Creates a new DumpProcessingWatcher with default settings.
     */
    public DumpProcessingWatcher(DumpProcessingStore store, Consumer<String> statusCallback) {
        this(store, statusCallback, DEFAULT_POLLING_INTERVAL_SECONDS);
    }
    
    /**
     * Creates a new DumpProcessingWatcher with custom polling interval.
     */
    public DumpProcessingWatcher(DumpProcessingStore store, Consumer<String> statusCallback, 
                                  int pollingIntervalSeconds) {
        this.store = store;
        this.statusCallback = statusCallback;
        this.pollingIntervalSeconds = pollingIntervalSeconds > 0 ? 
            pollingIntervalSeconds : DEFAULT_POLLING_INTERVAL_SECONDS;
        
        this.executor = new DumpScriptExecutor();
        this.processingFiles = ConcurrentHashMap.newKeySet();
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DumpProcessingWatcher-Scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // Thread pool for parallel processing (limited to 2 concurrent executions)
        this.processingExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "DumpProcessingWatcher-Processor");
            t.setDaemon(true);
            return t;
        });
        
        this.running = false;
    }
    
    /**
     * Starts the watcher.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        
        updateStatus("Starting dump processing watcher...");
        
        // Schedule periodic polling
        scheduler.scheduleAtFixedRate(
            this::pollDumpFolders,
            5, // Initial delay of 5 seconds
            pollingIntervalSeconds,
            TimeUnit.SECONDS
        );
        
        // Schedule periodic cleanup
        scheduler.scheduleAtFixedRate(
            this::cleanupOldRecords,
            1, // Initial delay of 1 hour
            CLEANUP_INTERVAL_HOURS,
            TimeUnit.HOURS
        );
        
        updateStatus("Dump processing watcher started (polling every " + pollingIntervalSeconds + "s)");
    }
    
    /**
     * Stops the watcher.
     */
    public void stop() {
        running = false;
        
        scheduler.shutdown();
        processingExecutor.shutdown();
        
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            processingExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        updateStatus("Dump processing watcher stopped");
    }
    
    /**
     * Polls all enabled dump folders for new .mdb files.
     */
    private void pollDumpFolders() {
        if (!running) {
            return;
        }
        
        try {
            List<DumpProcessConfig> configs = store.getEnabledConfigs();
            
            for (DumpProcessConfig config : configs) {
                try {
                    scanDumpFolder(config);
                    processReadyFiles(config);
                } catch (Exception e) {
                    System.err.println("Error processing config " + config.getServerName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error polling dump folders: " + e.getMessage());
        }
    }
    
    /**
     * Scans a dump folder for .mdb files and adds new ones to tracking.
     */
    private void scanDumpFolder(DumpProcessConfig config) {
        File dumpFolder = new File(config.getDumpFolder());
        
        if (!dumpFolder.exists() || !dumpFolder.isDirectory()) {
            return;
        }
        
        // Find all .mdb files
        File[] mdbFiles = dumpFolder.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(MDB_EXTENSION);
            }
        });
        
        if (mdbFiles == null || mdbFiles.length == 0) {
            return;
        }
        
        // Track which files we found
        Set<String> foundFiles = new HashSet<>();
        
        for (File mdbFile : mdbFiles) {
            String filePath = mdbFile.getAbsolutePath();
            foundFiles.add(filePath);
            
            try {
                // Check if we're already tracking this file
                if (store.findFileByPath(config.getId(), filePath).isEmpty()) {
                    // New file - add to tracking
                    DumpFileTracking tracking = new DumpFileTracking(
                        config.getId(),
                        mdbFile.getName(),
                        filePath,
                        mdbFile.length(),
                        System.currentTimeMillis(),
                        mdbFile.lastModified()
                    );
                    
                    store.addFileTracking(tracking);
                    updateStatus("New .mdb file detected: " + mdbFile.getName() + 
                        " [" + config.getServerName() + "]");
                }
            } catch (SQLException e) {
                System.err.println("Error adding file tracking for " + filePath + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Processes files that are ready (exceeded threshold).
     */
    private void processReadyFiles(DumpProcessConfig config) {
        List<DumpFileTracking> pendingFiles = store.getPendingFiles(config.getId());
        
        for (DumpFileTracking file : pendingFiles) {
            // Check if file has exceeded threshold
            if (!file.isReadyForProcessing(config.getThresholdMinutes())) {
                continue;
            }
            
            // Check if we're already processing this file
            if (processingFiles.contains(file.getFilePath())) {
                continue;
            }
            
            // Check if file still exists
            File mdbFile = new File(file.getFilePath());
            if (!mdbFile.exists()) {
                // File was removed - mark as completed (manually processed or deleted)
                try {
                    store.updateFileStatus(file.getId(), DumpFileTracking.STATUS_COMPLETED, 
                        "File no longer exists (manually processed or deleted)");
                } catch (SQLException e) {
                    System.err.println("Error updating file status: " + e.getMessage());
                }
                continue;
            }
            
            // Submit for processing
            processingFiles.add(file.getFilePath());
            processingExecutor.submit(() -> processFile(config, file));
        }
    }
    
    /**
     * Processes a single .mdb file.
     */
    private void processFile(DumpProcessConfig config, DumpFileTracking file) {
        try {
            updateStatus("Processing: " + file.getFileName() + " [" + config.getServerName() + "]");
            
            // Update status to PROCESSING
            store.updateFileStatus(file.getId(), DumpFileTracking.STATUS_PROCESSING, null);
            
            // Execute the script
            DumpScriptExecutor.ExecutionResult result = executor.execute(config);
            
            // Update config with last run info
            store.updateLastRunStatus(config.getId(), result.getStatusString(), result.getOutput());
            
            if (result.isSuccess()) {
                // Success
                store.updateFileStatus(file.getId(), DumpFileTracking.STATUS_COMPLETED, result.getOutput());
                updateStatus("Completed: " + file.getFileName() + " [" + config.getServerName() + 
                    "] (exit code: " + result.getExitCode() + ", duration: " + 
                    result.getDurationMillis() / 1000 + "s)");
            } else {
                // Failed
                store.updateFileStatus(file.getId(), DumpFileTracking.STATUS_FAILED, result.getOutput());
                updateStatus("Failed: " + file.getFileName() + " [" + config.getServerName() + 
                    "] - " + (result.isTimedOut() ? "TIMEOUT" : "exit code: " + result.getExitCode()));
                
                // Check if we should retry
                if (file.canRetry(MAX_RETRIES)) {
                    store.incrementRetryCount(file.getId());
                    updateStatus("Will retry: " + file.getFileName() + " (attempt " + 
                        (file.getRetryCount() + 1) + "/" + MAX_RETRIES + ")");
                }
            }
            
        } catch (SQLException e) {
            System.err.println("Database error processing file " + file.getFileName() + ": " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error processing file " + file.getFileName() + ": " + e.getMessage());
            try {
                store.updateFileStatus(file.getId(), DumpFileTracking.STATUS_FAILED, 
                    "Exception: " + e.getMessage());
            } catch (SQLException ex) {
                System.err.println("Error updating file status: " + ex.getMessage());
            }
        } finally {
            processingFiles.remove(file.getFilePath());
        }
    }
    
    /**
     * Manually triggers processing for a specific config.
     * Ignores the threshold and processes all pending files immediately.
     */
    public void triggerProcessing(int configId) {
        store.getConfigById(configId).ifPresent(config -> {
            if (!config.isEnabled()) {
                updateStatus("Config " + config.getServerName() + " is disabled");
                return;
            }
            
            // Scan for new files first
            scanDumpFolder(config);
            
            // Get all pending files (ignore threshold)
            List<DumpFileTracking> pendingFiles = store.getPendingFiles(configId);
            
            if (pendingFiles.isEmpty()) {
                updateStatus("No pending files for " + config.getServerName());
                return;
            }
            
            updateStatus("Manual trigger: processing " + pendingFiles.size() + 
                " file(s) for " + config.getServerName());
            
            for (DumpFileTracking file : pendingFiles) {
                if (!processingFiles.contains(file.getFilePath())) {
                    File mdbFile = new File(file.getFilePath());
                    if (mdbFile.exists()) {
                        processingFiles.add(file.getFilePath());
                        processingExecutor.submit(() -> processFile(config, file));
                    }
                }
            }
        });
    }
    
    /**
     * Cleans up old completed/failed records.
     */
    private void cleanupOldRecords() {
        try {
            int deleted = store.cleanupOldRecords(CLEANUP_INTERVAL_HOURS);
            if (deleted > 0) {
                updateStatus("Cleaned up " + deleted + " old dump processing records");
            }
        } catch (SQLException e) {
            System.err.println("Error cleaning up old records: " + e.getMessage());
        }
    }
    
    /**
     * Returns true if the watcher is running.
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Returns the number of files currently being processed.
     */
    public int getProcessingCount() {
        return processingFiles.size();
    }
    
    private void updateStatus(String status) {
        System.out.println("[DumpProcessing] " + status);
        if (statusCallback != null) {
            statusCallback.accept("[DumpProcessing] " + status);
        }
    }
}
