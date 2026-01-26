package com.logdashboard.model;

/**
 * Configuration for database dump processing.
 * Monitors a dump folder for .mdb files and executes ExtractMDB.do.sh script
 * when files have been sitting for longer than the threshold.
 */
public class DumpProcessConfig {
    
    // Supported database types
    public static final String DB_TYPE_SQLITE = "SQLITE";
    public static final String DB_TYPE_DATACOM = "DATACOM";
    
    private int id;
    private String serverName;       // Reference/label for this config
    private String dbFolder;         // Folder containing ExtractMDB.do.sh script
    private String dumpFolder;       // Directory to monitor for .mdb files
    private String dbType;           // SQLITE or DATACOM only
    private String javaPath;         // Path to Java installation
    private int thresholdMinutes;    // How long before triggering (default: 1)
    private String adminUser;        // Optional: run as this user via 'su'
    private String adminPassword;    // Optional: password for su authentication
    private boolean enabled;         // Enable/disable processing
    private String lastRunTime;      // Last execution timestamp (ISO format)
    private String lastRunStatus;    // SUCCESS, FAILED, RUNNING, null
    private String lastRunOutput;    // Last execution output/error message
    
    public DumpProcessConfig() {
        this.thresholdMinutes = 1;
        this.enabled = true;
    }
    
    public DumpProcessConfig(int id, String serverName, String dbFolder, String dumpFolder,
                             String dbType, String javaPath, int thresholdMinutes,
                             String adminUser, boolean enabled) {
        this.id = id;
        this.serverName = serverName;
        this.dbFolder = dbFolder;
        this.dumpFolder = dumpFolder;
        this.dbType = dbType;
        this.javaPath = javaPath;
        this.thresholdMinutes = thresholdMinutes;
        this.adminUser = adminUser;
        this.enabled = enabled;
    }
    
    /**
     * Validates that the dbType is one of the allowed values.
     */
    public static boolean isValidDbType(String dbType) {
        return DB_TYPE_SQLITE.equalsIgnoreCase(dbType) || 
               DB_TYPE_DATACOM.equalsIgnoreCase(dbType);
    }
    
    /**
     * Returns the normalized (uppercase) db type.
     */
    public static String normalizeDbType(String dbType) {
        if (dbType == null) return null;
        String upper = dbType.toUpperCase().trim();
        if (DB_TYPE_SQLITE.equals(upper)) return DB_TYPE_SQLITE;
        if (DB_TYPE_DATACOM.equals(upper)) return DB_TYPE_DATACOM;
        return null; // Invalid
    }
    
    /**
     * Escapes a path for safe use in shell commands by wrapping with single quotes
     * and escaping any single quotes within the path.
     */
    private static String shellQuote(String path) {
        if (path == null) return "''";
        // Escape single quotes by ending the single-quoted string, adding escaped quote, and restarting
        // e.g., "it's" becomes 'it'\''s'
        return "'" + path.replace("'", "'\\''") + "'";
    }
    
    /**
     * Builds the command to execute.
     * Format: ./ExtractMDB.do.sh <dbType> <javaPath> <dumpFolder>
     * Paths are properly quoted to handle spaces and special characters.
     */
    public String buildCommand() {
        return String.format("./ExtractMDB.do.sh %s %s %s", 
            dbType, shellQuote(javaPath), shellQuote(dumpFolder));
    }
    
    /**
     * Builds the full command with su for elevated privileges.
     * Uses z/OS compatible syntax with here-document:
     * - cd to dbFolder first
     * - Use here-document to send password and command to su
     * - If adminUser is set: su - <adminUser>
     * - If adminUser is empty: su (enters root/admin mode)
     * Paths are properly quoted to handle spaces and special characters.
     */
    public String buildFullCommand() {
        String baseCommand = buildCommand();
        boolean hasUser = adminUser != null && !adminUser.trim().isEmpty();
        boolean hasPassword = adminPassword != null && !adminPassword.isEmpty();
        
        String suTarget = hasUser ? "su - " + adminUser.trim() : "su";
        
        if (hasPassword) {
            // Use here-document to send password and command to su
            // The password goes first, then the command to execute
            return String.format("cd %s && %s << 'SUEOF'\n%s\n%s\nSUEOF", 
                shellQuote(dbFolder), 
                suTarget,
                adminPassword,
                baseCommand);
        }
        
        // No password - use here-document with just the command
        return String.format("cd %s && %s << 'SUEOF'\n%s\nSUEOF", 
            shellQuote(dbFolder), suTarget, baseCommand);
    }
    
    // Getters and Setters
    
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public String getServerName() {
        return serverName;
    }
    
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }
    
    public String getDbFolder() {
        return dbFolder;
    }
    
    public void setDbFolder(String dbFolder) {
        this.dbFolder = dbFolder;
    }
    
    public String getDumpFolder() {
        return dumpFolder;
    }
    
    public void setDumpFolder(String dumpFolder) {
        this.dumpFolder = dumpFolder;
    }
    
    public String getDbType() {
        return dbType;
    }
    
    public void setDbType(String dbType) {
        // Normalize to uppercase
        this.dbType = normalizeDbType(dbType);
    }
    
    public String getJavaPath() {
        return javaPath;
    }
    
    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
    }
    
    public int getThresholdMinutes() {
        return thresholdMinutes;
    }
    
    public void setThresholdMinutes(int thresholdMinutes) {
        this.thresholdMinutes = thresholdMinutes > 0 ? thresholdMinutes : 1;
    }
    
    public String getAdminUser() {
        return adminUser;
    }
    
    public void setAdminUser(String adminUser) {
        this.adminUser = adminUser;
    }
    
    public String getAdminPassword() {
        return adminPassword;
    }
    
    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public String getLastRunTime() {
        return lastRunTime;
    }
    
    public void setLastRunTime(String lastRunTime) {
        this.lastRunTime = lastRunTime;
    }
    
    public String getLastRunStatus() {
        return lastRunStatus;
    }
    
    public void setLastRunStatus(String lastRunStatus) {
        this.lastRunStatus = lastRunStatus;
    }
    
    public String getLastRunOutput() {
        return lastRunOutput;
    }
    
    public void setLastRunOutput(String lastRunOutput) {
        this.lastRunOutput = lastRunOutput;
    }
    
    @Override
    public String toString() {
        return "DumpProcessConfig{" +
                "id=" + id +
                ", serverName='" + serverName + '\'' +
                ", dbFolder='" + dbFolder + '\'' +
                ", dumpFolder='" + dumpFolder + '\'' +
                ", dbType='" + dbType + '\'' +
                ", thresholdMinutes=" + thresholdMinutes +
                ", enabled=" + enabled +
                ", lastRunStatus='" + lastRunStatus + '\'' +
                '}';
    }
}
