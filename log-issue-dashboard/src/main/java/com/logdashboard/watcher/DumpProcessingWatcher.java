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
    
    // Track configs currently being processed to avoid duplicate processing
    // Since the script processes the entire dump folder, we track by config ID
    private final Set<Integer> processingConfigs;
    
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
        this.processingConfigs = ConcurrentHashMap.newKeySet();
        
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
        
        // Recover files stuck in PROCESSING status from previous server run
        recoverStuckFiles();
        
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
     * Recovers files that were stuck in PROCESSING status when the server was restarted.
     * Resets them to PENDING so they can be retried.
     */
    private void recoverStuckFiles() {
        try {
            int recovered = store.resetStuckProcessingFiles();
            if (recovered > 0) {
                updateStatus("Recovered " + recovered + " file(s) stuck in PROCESSING status from previous run");
            }
        } catch (SQLException e) {
            System.err.println("Error recovering stuck files: " + e.getMessage());
        }
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
            
            if (configs.isEmpty()) {
                // No configs to poll - this is normal if no configurations are set up
                return;
            }
            
            for (DumpProcessConfig config : configs) {
                try {
                    // Log that we're checking this config's dump folder
                    File dumpFolder = new File(config.getDumpFolder());
                    if (dumpFolder.exists() && dumpFolder.isDirectory()) {
                        File[] mdbFiles = dumpFolder.listFiles((dir, name) -> 
                            name.toLowerCase().endsWith(MDB_EXTENSION));
                        int fileCount = mdbFiles != null ? mdbFiles.length : 0;
                        if (fileCount > 0) {
                            updateStatus("Polling " + config.getServerName() + " (ID:" + config.getId() + 
                                "): found " + fileCount + " .mdb file(s) in " + config.getDumpFolder());
                        }
                    } else {
                        updateStatus("WARNING: Dump folder not accessible: " + config.getDumpFolder() + 
                            " for config " + config.getServerName() + " (ID:" + config.getId() + ")");
                    }
                    
                    scanDumpFolder(config);
                    processReadyFiles(config);
                } catch (Exception e) {
                    System.err.println("Error processing config " + config.getServerName() + 
                        " (ID:" + config.getId() + "): " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            System.err.println("Error polling dump folders: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Scans a dump folder for .mdb files and adds new ones to tracking.
     */
    private void scanDumpFolder(DumpProcessConfig config) {
        File dumpFolder = new File(config.getDumpFolder());
        
        if (!dumpFolder.exists() || !dumpFolder.isDirectory()) {
            updateStatus("WARNING: Dump folder does not exist: " + config.getDumpFolder());
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
        
        int newFiles = 0;
        int existingFiles = 0;
        
        for (File mdbFile : mdbFiles) {
            String filePath = mdbFile.getAbsolutePath();
            
            try {
                // Check if we're already tracking this file
                var existing = store.findFileByPath(config.getId(), filePath);
                if (existing.isEmpty()) {
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
                    newFiles++;
                    updateStatus("New .mdb file detected: " + mdbFile.getName() + 
                        " [" + config.getServerName() + "]");
                } else {
                    existingFiles++;
                }
            } catch (SQLException e) {
                System.err.println("Error adding file tracking for " + filePath + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // Log summary
        if (newFiles > 0 || existingFiles > 0) {
            updateStatus("Scan complete for " + config.getServerName() + 
                ": " + newFiles + " new, " + existingFiles + " already tracked");
        }
    }
    
    /**
     * Processes files that are ready (exceeded threshold).
     * The script processes the entire dump folder, so we process once per config
     * when any file is ready, not per file.
     */
    private void processReadyFiles(DumpProcessConfig config) {
        // Use atomic add to prevent race conditions - returns false if already present
        if (!processingConfigs.add(config.getId())) {
            // Config is already being processed
            return;
        }
        
        try {
            List<DumpFileTracking> pendingFiles = store.getPendingFiles(config.getId());
            
            // Find files that are ready for processing
            List<DumpFileTracking> readyFiles = new java.util.ArrayList<>();
            for (DumpFileTracking file : pendingFiles) {
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
                
                // Check if file has exceeded threshold
                if (file.isReadyForProcessing(config.getThresholdMinutes())) {
                    readyFiles.add(file);
                }
            }
            
            if (readyFiles.isEmpty()) {
                // No files ready, remove from processing set
                processingConfigs.remove(config.getId());
                return;
            }
            
            // Submit config for processing - all ready files will be processed together
            processingExecutor.submit(() -> processConfig(config, readyFiles));
            
        } catch (Exception e) {
            // On error, remove from processing set
            processingConfigs.remove(config.getId());
            throw e;
        }
    }
    
    /**
     * Processes all ready files for a config.
     * The script processes the entire dump folder, so all ready files
     * are marked together based on the script result.
     */
    private void processConfig(DumpProcessConfig config, List<DumpFileTracking> files) {
        try {
            String fileNames = files.stream()
                .map(DumpFileTracking::getFileName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("unknown");
            updateStatus("Processing " + files.size() + " file(s): " + fileNames + " [" + config.getServerName() + "]");
            
            // Update status to PROCESSING for all files
            for (DumpFileTracking file : files) {
                try {
                    store.updateFileStatus(file.getId(), DumpFileTracking.STATUS_PROCESSING, null);
                } catch (SQLException e) {
                    System.err.println("Error updating file status: " + e.getMessage());
                }
            }
            
            // Execute the script once for the entire dump folder
            DumpScriptExecutor.ExecutionResult result = executor.execute(config);
            
            // Update config with last run info
            store.updateLastRunStatus(config.getId(), result.getStatusString(), result.getOutput());
            
            // Determine the appropriate status based on result
            String newStatus;
            boolean shouldCleanup = false;
            
            if (result.isSuccess()) {
                // True success: exit code 0, no timeout, no exceptions in output
                newStatus = DumpFileTracking.STATUS_COMPLETED;
                shouldCleanup = true; // Clean up tracking record after true success
                updateStatus("Completed: " + files.size() + " file(s) [" + config.getServerName() + 
                    "] (exit code: " + result.getExitCode() + ", duration: " + 
                    result.getDurationMillis() / 1000 + "s, output clean)");
            } else if (result.hasOutputWarnings()) {
                // Exit code 0 but exceptions found in output - mark as completed with errors
                newStatus = DumpFileTracking.STATUS_COMPLETED_WITH_ERRORS;
                shouldCleanup = false; // Keep record for review
                updateStatus("Completed with errors: " + files.size() + " file(s) [" + config.getServerName() + 
                    "] - Exceptions detected in output: " + String.join(", ", result.getDetectedExceptions()));
            } else {
                // Failed - exit code != 0 or timed out
                newStatus = DumpFileTracking.STATUS_FAILED;
                shouldCleanup = false;
                updateStatus("Failed: " + files.size() + " file(s) [" + config.getServerName() + 
                    "] - " + (result.isTimedOut() ? "TIMEOUT" : "exit code: " + result.getExitCode()));
            }
            
            // Update status for all files
            for (DumpFileTracking file : files) {
                try {
                    if (shouldCleanup) {
                        // True success: delete the physical dump file from the folder
                        File dumpFile = new File(file.getFilePath());
                        if (dumpFile.exists()) {
                            if (dumpFile.delete()) {
                                updateStatus("Deleted dump file: " + file.getFileName());
                            } else {
                                System.err.println("Warning: Failed to delete dump file: " + file.getFilePath());
                                updateStatus("Warning: Could not delete dump file: " + file.getFileName());
                            }
                        } else {
                            updateStatus("Dump file already removed: " + file.getFileName());
                        }
                        
                        // Delete the tracking record (data is "gone")
                        store.deleteFileTracking(file.getId());
                        updateStatus("Cleaned up tracking record for: " + file.getFileName());
                    } else {
                        // Not a clean success: keep the record with status and output
                        store.updateFileStatus(file.getId(), newStatus, result.getOutput());
                        
                        // Handle retries for failed files (not for completed_with_errors)
                        if (newStatus.equals(DumpFileTracking.STATUS_FAILED) && file.getRetryCount() < MAX_RETRIES) {
                            store.incrementRetryCount(file.getId());
                        }
                    }
                } catch (SQLException e) {
                    System.err.println("Error updating file status: " + e.getMessage());
                }
            }
            
        } catch (SQLException e) {
            System.err.println("Database error processing config " + config.getServerName() + ": " + e.getMessage());
            // Mark files as FAILED to prevent them from being stuck in PROCESSING forever
            // Also handle retries for transient database errors
            for (DumpFileTracking file : files) {
                try {
                    store.updateFileStatus(file.getId(), DumpFileTracking.STATUS_FAILED, 
                        "Database error: " + e.getMessage());
                    // Handle retries for exception failures too
                    if (file.getRetryCount() < MAX_RETRIES) {
                        store.incrementRetryCount(file.getId());
                    }
                } catch (SQLException ex) {
                    System.err.println("Error updating file status: " + ex.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing config " + config.getServerName() + ": " + e.getMessage());
            for (DumpFileTracking file : files) {
                try {
                    store.updateFileStatus(file.getId(), DumpFileTracking.STATUS_FAILED, 
                        "Exception: " + e.getMessage());
                    // Handle retries for exception failures too
                    if (file.getRetryCount() < MAX_RETRIES) {
                        store.incrementRetryCount(file.getId());
                    }
                } catch (SQLException ex) {
                    System.err.println("Error updating file status: " + ex.getMessage());
                }
            }
        } finally {
            processingConfigs.remove(config.getId());
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
            
            // Use atomic add to prevent race conditions - returns false if already present
            if (!processingConfigs.add(configId)) {
                updateStatus("Config " + config.getServerName() + " is already being processed");
                return;
            }
            
            try {
                // Scan for new files first
                scanDumpFolder(config);
                
                // Get all pending files (ignore threshold)
                List<DumpFileTracking> pendingFiles = store.getPendingFiles(configId);
                
                // Filter to files that still exist
                List<DumpFileTracking> existingFiles = new java.util.ArrayList<>();
                for (DumpFileTracking file : pendingFiles) {
                    File mdbFile = new File(file.getFilePath());
                    if (mdbFile.exists()) {
                        existingFiles.add(file);
                    } else {
                        try {
                            store.updateFileStatus(file.getId(), DumpFileTracking.STATUS_COMPLETED, 
                                "File no longer exists (manually processed or deleted)");
                        } catch (SQLException e) {
                            System.err.println("Error updating file status: " + e.getMessage());
                        }
                    }
                }
                
                if (existingFiles.isEmpty()) {
                    updateStatus("No pending files for " + config.getServerName());
                    processingConfigs.remove(configId);
                    return;
                }
                
                updateStatus("Manual trigger: processing " + existingFiles.size() + 
                    " file(s) for " + config.getServerName());
                
                // Submit for processing - all files processed together
                processingExecutor.submit(() -> processConfig(config, existingFiles));
                
            } catch (Exception e) {
                // On error, remove from processing set
                processingConfigs.remove(configId);
                throw e;
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
     * Returns the number of configs currently being processed.
     */
    public int getProcessingCount() {
        return processingConfigs.size();
    }
    
    private void updateStatus(String status) {
        System.out.println("[DumpProcessing] " + status);
        if (statusCallback != null) {
            statusCallback.accept("[DumpProcessing] " + status);
        }
    }
}
