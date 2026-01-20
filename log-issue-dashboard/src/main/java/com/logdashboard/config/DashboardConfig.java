package com.logdashboard.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
    private List<String> exclusionPatterns;  // Patterns to exclude (false positives)
    private List<String> criticalPatterns;   // Patterns for CRITICAL severity
    private List<Map<String, Object>> customRules;  // Custom detection rules
    private int pollingIntervalSeconds;
    private int maxIssuesDisplayed;
    private boolean enableSound;
    private String windowTitle;
    private int webServerPort;
    
    // Enhanced detection options
    private int contextLinesBefore;  // Lines of context to capture before issue
    private int contextLinesAfter;   // Lines of context to capture after issue
    private boolean enableDeduplication;  // Deduplicate similar issues within time window
    private boolean parseJsonLogs;   // Parse JSON-formatted log lines
    
    // Database configuration
    private String storageType; // "memory" or "h2"
    private String databasePath; // Path to H2 database file (without .mv.db extension)
    
    // Admin credentials for infrastructure management
    private String adminUsername;
    private String adminPassword;
    
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
        // Default exclusion patterns for common false positives
        this.exclusionPatterns = Arrays.asList(
            ".*Success:.*Failed: 0.*",  // Success messages with zero failures
            ".*\\bFailed: 0\\b.*\\bSkipped: 0\\b.*"  // Summary lines with zero failures
        );
        // Default critical patterns (severe issues)
        this.criticalPatterns = new ArrayList<>(); // Uses built-in patterns if empty
        this.customRules = new ArrayList<>();
        this.pollingIntervalSeconds = 2;
        this.maxIssuesDisplayed = Integer.MAX_VALUE; // No limit
        this.enableSound = false;
        this.windowTitle = "Log Issue Dashboard";
        this.webServerPort = 8080;
        
        // Enhanced detection defaults
        this.contextLinesBefore = 2;  // Capture 2 lines before issue
        this.contextLinesAfter = 5;   // Capture 5 lines after issue
        this.enableDeduplication = true;  // Enable deduplication by default
        this.parseJsonLogs = true;    // Parse JSON logs by default
        
        this.storageType = "h2"; // Default to H2 for persistence
        this.databasePath = "data/log-dashboard"; // Default database path
        this.adminUsername = null; // Must be configured in config file
        this.adminPassword = null; // Must be configured in config file
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

    public List<String> getExclusionPatterns() {
        return exclusionPatterns;
    }

    public void setExclusionPatterns(List<String> exclusionPatterns) {
        this.exclusionPatterns = exclusionPatterns;
    }

    public List<String> getCriticalPatterns() {
        return criticalPatterns;
    }

    public void setCriticalPatterns(List<String> criticalPatterns) {
        this.criticalPatterns = criticalPatterns;
    }

    public List<Map<String, Object>> getCustomRules() {
        return customRules;
    }

    public void setCustomRules(List<Map<String, Object>> customRules) {
        this.customRules = customRules;
    }

    public int getContextLinesBefore() {
        return contextLinesBefore;
    }

    public void setContextLinesBefore(int contextLinesBefore) {
        this.contextLinesBefore = contextLinesBefore;
    }

    public int getContextLinesAfter() {
        return contextLinesAfter;
    }

    public void setContextLinesAfter(int contextLinesAfter) {
        this.contextLinesAfter = contextLinesAfter;
    }

    public boolean isEnableDeduplication() {
        return enableDeduplication;
    }

    public void setEnableDeduplication(boolean enableDeduplication) {
        this.enableDeduplication = enableDeduplication;
    }

    public boolean isParseJsonLogs() {
        return parseJsonLogs;
    }

    public void setParseJsonLogs(boolean parseJsonLogs) {
        this.parseJsonLogs = parseJsonLogs;
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
    
    public String getAdminUsername() {
        return adminUsername;
    }
    
    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }
    
    public String getAdminPassword() {
        return adminPassword;
    }
    
    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
    
    /**
     * Returns true if admin credentials are configured.
     */
    public boolean hasAdminCredentials() {
        return adminUsername != null && !adminUsername.isEmpty() 
            && adminPassword != null && !adminPassword.isEmpty();
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
