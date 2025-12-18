package com.logdashboard.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration class for the Log Issue Dashboard.
 */
public class DashboardConfig {
    
    private List<String> watchPaths;  // Legacy: simple paths without server names
    private List<ServerPath> servers; // New: server-based paths
    private List<String> filePatterns;
    private List<String> exceptionPatterns;
    private List<String> errorPatterns;
    private List<String> warningPatterns;
    private int pollingIntervalSeconds;
    private int maxIssuesDisplayed;
    private boolean enableSound;
    private String windowTitle;
    private int webServerPort;
    
    // Database configuration
    private String storageType; // "memory" or "h2"
    private String databasePath; // Path to H2 database file (without .mv.db extension)
    
    public DashboardConfig() {
        // Default configuration
        this.watchPaths = new ArrayList<>();
        this.servers = new ArrayList<>();
        this.filePatterns = Arrays.asList("*.log", "*.txt", "*.out");
        this.exceptionPatterns = Arrays.asList(
            ".*Exception.*",
            ".*Error:.*",
            ".*at\\s+[\\w.$]+\\([^)]+\\).*",
            ".*Caused by:.*",
            ".*FATAL.*",
            ".*Throwable.*"
        );
        this.errorPatterns = Arrays.asList(
            ".*\\bERROR\\b.*",
            ".*\\bFAILED\\b.*",
            ".*\\bFAILURE\\b.*"
        );
        this.warningPatterns = Arrays.asList(
            ".*\\bWARN\\b.*",
            ".*\\bWARNING\\b.*"
        );
        this.pollingIntervalSeconds = 2;
        this.maxIssuesDisplayed = 500;
        this.enableSound = false;
        this.windowTitle = "Log Issue Dashboard";
        this.webServerPort = 8080;
        this.storageType = "h2"; // Default to H2 for persistence
        this.databasePath = "data/log-dashboard"; // Default database path
    }

    public List<String> getWatchPaths() {
        return watchPaths;
    }

    public void setWatchPaths(List<String> watchPaths) {
        this.watchPaths = watchPaths;
    }

    public List<ServerPath> getServers() {
        return servers;
    }

    public void setServers(List<ServerPath> servers) {
        this.servers = servers;
    }

    public List<String> getFilePatterns() {
        return filePatterns;
    }

    public void setFilePatterns(List<String> filePatterns) {
        this.filePatterns = filePatterns;
    }

    public List<String> getExceptionPatterns() {
        return exceptionPatterns;
    }

    public void setExceptionPatterns(List<String> exceptionPatterns) {
        this.exceptionPatterns = exceptionPatterns;
    }

    public List<String> getErrorPatterns() {
        return errorPatterns;
    }

    public void setErrorPatterns(List<String> errorPatterns) {
        this.errorPatterns = errorPatterns;
    }

    public List<String> getWarningPatterns() {
        return warningPatterns;
    }

    public void setWarningPatterns(List<String> warningPatterns) {
        this.warningPatterns = warningPatterns;
    }

    public int getPollingIntervalSeconds() {
        return pollingIntervalSeconds;
    }

    public void setPollingIntervalSeconds(int pollingIntervalSeconds) {
        this.pollingIntervalSeconds = pollingIntervalSeconds;
    }

    public int getMaxIssuesDisplayed() {
        return maxIssuesDisplayed;
    }

    public void setMaxIssuesDisplayed(int maxIssuesDisplayed) {
        this.maxIssuesDisplayed = maxIssuesDisplayed;
    }

    public boolean isEnableSound() {
        return enableSound;
    }

    public void setEnableSound(boolean enableSound) {
        this.enableSound = enableSound;
    }

    public String getWindowTitle() {
        return windowTitle;
    }

    public void setWindowTitle(String windowTitle) {
        this.windowTitle = windowTitle;
    }

    public int getWebServerPort() {
        return webServerPort;
    }

    public void setWebServerPort(int webServerPort) {
        this.webServerPort = webServerPort;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public String getDatabasePath() {
        return databasePath;
    }

    public void setDatabasePath(String databasePath) {
        this.databasePath = databasePath;
    }
    
    /**
     * Returns true if H2 database storage should be used.
     */
    public boolean useH2Storage() {
        return "h2".equalsIgnoreCase(storageType);
    }

    @Override
    public String toString() {
        return "DashboardConfig{" +
                "watchPaths=" + watchPaths +
                ", filePatterns=" + filePatterns +
                ", pollingIntervalSeconds=" + pollingIntervalSeconds +
                ", maxIssuesDisplayed=" + maxIssuesDisplayed +
                '}';
    }
}
