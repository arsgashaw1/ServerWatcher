package com.logdashboard.model;

/**
 * Tracks individual .mdb files being monitored for dump processing.
 * Records when files are first seen and their processing status.
 */
public class DumpFileTracking {
    
    // File processing statuses
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS"; // Exit 0 but exceptions in output
    public static final String STATUS_FAILED = "FAILED";
    
    private int id;
    private int configId;            // FK to DumpProcessConfig
    private String fileName;         // e.g., "data.mdb"
    private String filePath;         // Full path to the file
    private long fileSize;           // Size in bytes
    private long firstSeenTime;      // When we first detected this file (epoch millis)
    private long lastModifiedTime;   // File's last modified timestamp (epoch millis)
    private String status;           // PENDING, PROCESSING, COMPLETED, FAILED
    private String processStartTime; // When extraction started (ISO format)
    private String processEndTime;   // When extraction finished (ISO format)
    private String processOutput;    // stdout/stderr from script
    private int retryCount;          // Number of retry attempts
    
    public DumpFileTracking() {
        this.status = STATUS_PENDING;
        this.retryCount = 0;
    }
    
    public DumpFileTracking(int configId, String fileName, String filePath, 
                            long fileSize, long firstSeenTime, long lastModifiedTime) {
        this.configId = configId;
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.firstSeenTime = firstSeenTime;
        this.lastModifiedTime = lastModifiedTime;
        this.status = STATUS_PENDING;
        this.retryCount = 0;
    }
    
    /**
     * Checks if this file has been waiting longer than the threshold.
     * @param thresholdMinutes The threshold in minutes
     * @return true if the file age exceeds the threshold
     */
    public boolean hasExceededThreshold(int thresholdMinutes) {
        long thresholdMillis = thresholdMinutes * 60 * 1000L;
        long age = System.currentTimeMillis() - firstSeenTime;
        return age >= thresholdMillis;
    }
    
    /**
     * Returns the age of this file in minutes since first seen.
     */
    public long getAgeMinutes() {
        return (System.currentTimeMillis() - firstSeenTime) / (60 * 1000L);
    }
    
    /**
     * Checks if this file is ready for processing (pending and exceeded threshold).
     */
    public boolean isReadyForProcessing(int thresholdMinutes) {
        return STATUS_PENDING.equals(status) && hasExceededThreshold(thresholdMinutes);
    }
    
    /**
     * Checks if this file can be retried (failed but under retry limit).
     */
    public boolean canRetry(int maxRetries) {
        return STATUS_FAILED.equals(status) && retryCount < maxRetries;
    }
    
    // Getters and Setters
    
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public int getConfigId() {
        return configId;
    }
    
    public void setConfigId(int configId) {
        this.configId = configId;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    
    public String getFilePath() {
        return filePath;
    }
    
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
    
    public long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }
    
    public long getFirstSeenTime() {
        return firstSeenTime;
    }
    
    public void setFirstSeenTime(long firstSeenTime) {
        this.firstSeenTime = firstSeenTime;
    }
    
    public long getLastModifiedTime() {
        return lastModifiedTime;
    }
    
    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getProcessStartTime() {
        return processStartTime;
    }
    
    public void setProcessStartTime(String processStartTime) {
        this.processStartTime = processStartTime;
    }
    
    public String getProcessEndTime() {
        return processEndTime;
    }
    
    public void setProcessEndTime(String processEndTime) {
        this.processEndTime = processEndTime;
    }
    
    public String getProcessOutput() {
        return processOutput;
    }
    
    public void setProcessOutput(String processOutput) {
        this.processOutput = processOutput;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
    
    public void incrementRetryCount() {
        this.retryCount++;
    }
    
    @Override
    public String toString() {
        return "DumpFileTracking{" +
                "id=" + id +
                ", configId=" + configId +
                ", fileName='" + fileName + '\'' +
                ", status='" + status + '\'' +
                ", ageMinutes=" + getAgeMinutes() +
                ", retryCount=" + retryCount +
                '}';
    }
}
