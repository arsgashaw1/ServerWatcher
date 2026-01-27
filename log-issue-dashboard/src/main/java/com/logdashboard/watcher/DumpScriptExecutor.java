package com.logdashboard.watcher;

import com.logdashboard.model.DumpProcessConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
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
    
    // Patterns to detect exceptions and errors in output that indicate failure
    // even if exit code is 0
    private static final Pattern[] EXCEPTION_PATTERNS = {
        // Java exceptions
        Pattern.compile("(?i)Exception\\s*:", Pattern.MULTILINE),
        Pattern.compile("(?i)^\\s*at\\s+[\\w.$]+\\([^)]+\\)\\s*$", Pattern.MULTILINE), // Stack trace line
        Pattern.compile("(?i)java\\.lang\\.\\w*Exception", Pattern.MULTILINE),
        Pattern.compile("(?i)java\\.io\\.\\w*Exception", Pattern.MULTILINE),
        Pattern.compile("(?i)java\\.sql\\.\\w*Exception", Pattern.MULTILINE),
        // SQL errors
        Pattern.compile("(?i)SQL\\s*Error", Pattern.MULTILINE),
        Pattern.compile("(?i)SQLSTATE", Pattern.MULTILINE),
        // General error patterns
        Pattern.compile("(?i)^ERROR[:\\s]", Pattern.MULTILINE),
        Pattern.compile("(?i)\\bFATAL\\b", Pattern.MULTILINE),
        Pattern.compile("(?i)\\bOOM\\b|OutOfMemory", Pattern.MULTILINE),
        Pattern.compile("(?i)NullPointerException", Pattern.MULTILINE),
        Pattern.compile("(?i)ArrayIndexOutOfBoundsException", Pattern.MULTILINE),
        Pattern.compile("(?i)ClassNotFoundException", Pattern.MULTILINE),
        Pattern.compile("(?i)NoSuchMethodException", Pattern.MULTILINE),
        Pattern.compile("(?i)FileNotFoundException", Pattern.MULTILINE),
        Pattern.compile("(?i)IOException", Pattern.MULTILINE),
        Pattern.compile("(?i)SQLException", Pattern.MULTILINE),
        // Datacom/MDB specific errors
        Pattern.compile("(?i)extraction\\s+failed", Pattern.MULTILINE),
        Pattern.compile("(?i)database\\s+error", Pattern.MULTILINE),
        Pattern.compile("(?i)connection\\s+failed", Pattern.MULTILINE),
        Pattern.compile("(?i)unable\\s+to\\s+(open|read|write|connect)", Pattern.MULTILINE)
    };
    
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
            boolean outputTruncated = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!outputTruncated) {
                        output.append(line).append("\n");
                        // Limit output size to prevent memory issues
                        if (output.length() > 100000) { // 100KB limit
                            output.append("\n... [output truncated]\n");
                            outputTruncated = true;
                            // Continue reading to drain the output and prevent SIGPIPE
                        }
                    }
                    // When truncated, we still read lines but don't store them
                    // This allows the process to complete normally
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
     * Checks if the output contains exception patterns that indicate failure.
     * This is used to detect failures even when exit code is 0.
     * 
     * @param output The script output to check
     * @return List of detected exception patterns/messages
     */
    public static List<String> detectExceptionsInOutput(String output) {
        List<String> exceptions = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return exceptions;
        }
        
        for (Pattern pattern : EXCEPTION_PATTERNS) {
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                // Extract a snippet of the matched content
                String match = matcher.group();
                // Limit the snippet size
                if (match.length() > 100) {
                    match = match.substring(0, 100) + "...";
                }
                exceptions.add(match.trim());
                
                // Limit total exceptions found to prevent huge lists
                if (exceptions.size() >= 10) {
                    break;
                }
            }
        }
        
        return exceptions;
    }
    
    /**
     * Checks if the output indicates a clean completion without exceptions.
     * 
     * @param output The script output to check
     * @return true if output contains no exception patterns
     */
    public static boolean isOutputClean(String output) {
        return detectExceptionsInOutput(output).isEmpty();
    }
    
    /**
     * Result of script execution.
     */
    public static class ExecutionResult {
        private final int exitCode;
        private final String output;
        private final long durationMillis;
        private final boolean timedOut;
        private final List<String> detectedExceptions;
        private final boolean outputClean;
        
        public ExecutionResult(int exitCode, String output, long durationMillis, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.durationMillis = durationMillis;
            this.timedOut = timedOut;
            // Analyze output for exceptions
            this.detectedExceptions = detectExceptionsInOutput(output);
            this.outputClean = this.detectedExceptions.isEmpty();
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
        
        /**
         * Returns true if the output contains no exception patterns.
         */
        public boolean isOutputClean() {
            return outputClean;
        }
        
        /**
         * Returns the list of detected exception patterns in the output.
         */
        public List<String> getDetectedExceptions() {
            return detectedExceptions;
        }
        
        /**
         * Returns true if the execution was successful AND output is clean.
         * A true success requires:
         * - Exit code 0
         * - Not timed out
         * - No exceptions detected in output
         */
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut && outputClean;
        }
        
        /**
         * Returns true if exit code is 0 but output contains exceptions.
         * This indicates a partial success or undetected error.
         */
        public boolean hasOutputWarnings() {
            return exitCode == 0 && !timedOut && !outputClean;
        }
        
        public String getStatusString() {
            if (timedOut) return "TIMEOUT";
            if (exitCode != 0) return "FAILED";
            if (!outputClean) return "COMPLETED_WITH_ERRORS";
            return "SUCCESS";
        }
        
        /**
         * Returns a summary of detected issues for logging/display.
         */
        public String getIssueSummary() {
            if (isSuccess()) {
                return "Completed successfully";
            }
            
            StringBuilder sb = new StringBuilder();
            if (timedOut) {
                sb.append("Process timed out. ");
            }
            if (exitCode != 0) {
                sb.append("Exit code: ").append(exitCode).append(". ");
            }
            if (!outputClean) {
                sb.append("Exceptions detected in output: ");
                sb.append(String.join(", ", detectedExceptions));
            }
            return sb.toString();
        }
        
        @Override
        public String toString() {
            return "ExecutionResult{" +
                    "exitCode=" + exitCode +
                    ", timedOut=" + timedOut +
                    ", outputClean=" + outputClean +
                    ", durationMillis=" + durationMillis +
                    ", outputLength=" + (output != null ? output.length() : 0) +
                    ", exceptionsFound=" + detectedExceptions.size() +
                    '}';
        }
    }
}
