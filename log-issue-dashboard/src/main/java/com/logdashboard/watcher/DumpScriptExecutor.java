package com.logdashboard.watcher;

import com.logdashboard.model.DumpProcessConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Executes the ExtractMDB.do.sh script for database dump processing.
 * Handles command building, execution, and output capture.
 */
public class DumpScriptExecutor {
    
    // Default timeout: 30 minutes
    private static final long DEFAULT_TIMEOUT_MINUTES = 30;
    
    // Pattern to detect potentially dangerous characters in paths
    // Includes newline characters to prevent command injection via newlines
    private static final Pattern DANGEROUS_CHARS = Pattern.compile("[;&|`$(){}\\[\\]<>\n\r\u0000]");
    
    private final long timeoutMinutes;
    
    public DumpScriptExecutor() {
        this.timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;
    }
    
    public DumpScriptExecutor(long timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes > 0 ? timeoutMinutes : DEFAULT_TIMEOUT_MINUTES;
    }
    
    /**
     * Executes the dump processing script for the given configuration.
     * 
     * @param config The dump process configuration
     * @return ExecutionResult containing exit code, output, and duration
     */
    public ExecutionResult execute(DumpProcessConfig config) {
        long startTime = System.currentTimeMillis();
        
        // Validate config
        String validationError = validateConfig(config);
        if (validationError != null) {
            return new ExecutionResult(-1, validationError, 0, false);
        }
        
        // Check if script exists
        File scriptFile = new File(config.getDbFolder(), "ExtractMDB.do.sh");
        if (!scriptFile.exists()) {
            return new ExecutionResult(-1, 
                "Script not found: " + scriptFile.getAbsolutePath(), 0, false);
        }
        
        Process process = null;
        try {
            // Always execute with su (runs as root by default, or as specified adminUser)
            String command = config.buildFullCommand();
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
            
            // Redirect error stream to output stream
            processBuilder.redirectErrorStream(true);
            
            // Set working directory
            processBuilder.directory(new File(config.getDbFolder()));
            
            // Start the process
            process = processBuilder.start();
            
            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    // Limit output size to prevent memory issues
                    if (output.length() > 100000) { // 100KB limit
                        output.append("\n... [output truncated]\n");
                        break;
                    }
                }
            }
            
            // Wait for process with timeout
            boolean completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            long duration = System.currentTimeMillis() - startTime;
            
            if (!completed) {
                process.destroyForcibly();
                return new ExecutionResult(-1, 
                    output.toString() + "\n[TIMEOUT: Process killed after " + timeoutMinutes + " minutes]",
                    duration, true);
            }
            
            int exitCode = process.exitValue();
            return new ExecutionResult(exitCode, output.toString(), duration, false);
            
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            // Ensure process is destroyed on exception
            if (process != null) {
                process.destroyForcibly();
            }
            return new ExecutionResult(-1, "IO Error: " + e.getMessage(), duration, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long duration = System.currentTimeMillis() - startTime;
            // Ensure process is destroyed on exception
            if (process != null) {
                process.destroyForcibly();
            }
            return new ExecutionResult(-1, "Interrupted: " + e.getMessage(), duration, false);
        }
    }
    
    /**
     * Validates the configuration for security and correctness.
     * 
     * @param config The configuration to validate
     * @return Error message if invalid, null if valid
     */
    public String validateConfig(DumpProcessConfig config) {
        if (config == null) {
            return "Configuration is null";
        }
        
        // Validate required fields
        if (isBlank(config.getDbFolder())) {
            return "DB folder is required";
        }
        if (isBlank(config.getDumpFolder())) {
            return "Dump folder is required";
        }
        if (isBlank(config.getDbType())) {
            return "DB type is required";
        }
        if (isBlank(config.getJavaPath())) {
            return "Java path is required";
        }
        
        // Validate DB type
        if (!DumpProcessConfig.isValidDbType(config.getDbType())) {
            return "Invalid DB type: " + config.getDbType() + ". Must be SQLITE or DATACOM";
        }
        
        // Security: Check for dangerous characters in paths
        if (hasDangerousChars(config.getDbFolder())) {
            return "DB folder contains invalid characters";
        }
        if (hasDangerousChars(config.getDumpFolder())) {
            return "Dump folder contains invalid characters";
        }
        if (hasDangerousChars(config.getJavaPath())) {
            return "Java path contains invalid characters";
        }
        if (config.getAdminUser() != null && hasDangerousChars(config.getAdminUser())) {
            return "Admin user contains invalid characters";
        }
        
        // Validate admin user format (alphanumeric, underscore, hyphen only)
        if (config.getAdminUser() != null && !config.getAdminUser().trim().isEmpty()) {
            if (!config.getAdminUser().matches("^[a-zA-Z0-9_-]+$")) {
                return "Admin user must be alphanumeric (with _ or - allowed)";
            }
        }
        
        // Check if db folder exists
        File dbFolder = new File(config.getDbFolder());
        if (!dbFolder.exists()) {
            return "DB folder does not exist: " + config.getDbFolder();
        }
        if (!dbFolder.isDirectory()) {
            return "DB folder is not a directory: " + config.getDbFolder();
        }
        
        // Check if dump folder exists
        File dumpFolder = new File(config.getDumpFolder());
        if (!dumpFolder.exists()) {
            return "Dump folder does not exist: " + config.getDumpFolder();
        }
        if (!dumpFolder.isDirectory()) {
            return "Dump folder is not a directory: " + config.getDumpFolder();
        }
        
        return null; // Valid
    }
    
    /**
     * Checks if a string contains potentially dangerous shell characters.
     */
    private boolean hasDangerousChars(String s) {
        return s != null && DANGEROUS_CHARS.matcher(s).find();
    }
    
    /**
     * Checks if a string is null or blank.
     */
    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
    
    /**
     * Result of script execution.
     */
    public static class ExecutionResult {
        private final int exitCode;
        private final String output;
        private final long durationMillis;
        private final boolean timedOut;
        
        public ExecutionResult(int exitCode, String output, long durationMillis, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.durationMillis = durationMillis;
            this.timedOut = timedOut;
        }
        
        public int getExitCode() {
            return exitCode;
        }
        
        public String getOutput() {
            return output;
        }
        
        public long getDurationMillis() {
            return durationMillis;
        }
        
        public boolean isTimedOut() {
            return timedOut;
        }
        
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
        
        public String getStatusString() {
            if (timedOut) return "TIMEOUT";
            return exitCode == 0 ? "SUCCESS" : "FAILED";
        }
        
        @Override
        public String toString() {
            return "ExecutionResult{" +
                    "exitCode=" + exitCode +
                    ", timedOut=" + timedOut +
                    ", durationMillis=" + durationMillis +
                    ", outputLength=" + (output != null ? output.length() : 0) +
                    '}';
        }
    }
}
