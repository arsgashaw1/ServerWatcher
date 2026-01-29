package com.logdashboard.parser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.logdashboard.config.DashboardConfig;
import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced log parser with smart issue detection capabilities.
 * 
 * Features:
 * - Multi-pattern matching with severity classification
 * - CRITICAL severity detection for severe issues
 * - Multi-line log entry support
 * - JSON/structured log parsing
 * - Context capture (surrounding lines)
 * - Smart deduplication within time windows
 * - Custom rule support
 */
public class LogParser {
    
    private final List<Pattern> exceptionPatterns;
    private final List<Pattern> errorPatterns;
    private final List<Pattern> warningPatterns;
    private final List<Pattern> exclusionPatterns;
    private final List<Pattern> criticalPatterns;
    private final List<CustomRule> customRules;
    
    // Deduplication cache: fingerprint -> last seen timestamp
    private final Map<String, Long> recentIssueFingerprints = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 5000; // 5 second window for deduplication
    
    // Pattern to detect stack trace elements
    private static final Pattern STACK_TRACE_PATTERN = 
        Pattern.compile("^\\s+at\\s+[\\w.$]+\\([^)]+\\).*$");
    
    // Pattern to detect "Caused by:" lines
    private static final Pattern CAUSED_BY_PATTERN = 
        Pattern.compile("^Caused by:.*$");
    
    // Pattern to detect "... N more" lines in stack traces
    private static final Pattern MORE_PATTERN = 
        Pattern.compile("^\\s*\\.\\.\\.\\s*\\d+\\s+more\\s*$");
    
    // Pattern to detect log level in standard formats
    private static final Pattern LOG_LEVEL_PATTERN = 
        Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE|CRITICAL)\\b", Pattern.CASE_INSENSITIVE);
    
    // Pattern to detect JSON log lines
    private static final Pattern JSON_LOG_PATTERN = 
        Pattern.compile("^\\s*\\{.*\"(level|severity|log_level|loglevel)\".*\\}\\s*$", Pattern.CASE_INSENSITIVE);
    
    // Pattern to detect timestamps at the start of lines
    private static final Pattern TIMESTAMP_PATTERN = 
        Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}[T\\s]\\d{2}:\\d{2}");
    
    // Built-in critical patterns for severe issues
    private static final List<Pattern> BUILTIN_CRITICAL_PATTERNS = Arrays.asList(
        // OutOfMemory errors
        Pattern.compile(".*OutOfMemory.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*java\\.lang\\.OutOfMemoryError.*"),
        Pattern.compile(".*GC overhead limit exceeded.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*Java heap space.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*unable to create new native thread.*", Pattern.CASE_INSENSITIVE),
        
        // Thread/Deadlock issues
        Pattern.compile(".*deadlock.*detected.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*ThreadDeath.*"),
        Pattern.compile(".*DEADLOCK.*", Pattern.CASE_INSENSITIVE),
        
        // System crashes
        Pattern.compile(".*FATAL.*ERROR.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*system.*crash.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*JVM.*crash.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*core\\s+dumped.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*Segmentation fault.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*SIGSEGV.*"),
        Pattern.compile(".*SIGKILL.*"),
        Pattern.compile(".*SIGABRT.*"),
        
        // Database critical
        Pattern.compile(".*database.*connection.*lost.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*too many connections.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*tablespace.*full.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*disk.*full.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*no space left.*", Pattern.CASE_INSENSITIVE),
        
        // Security critical
        Pattern.compile(".*authentication.*fail.*multiple.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*unauthorized.*access.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*SSL.*handshake.*fail.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*certificate.*expired.*", Pattern.CASE_INSENSITIVE),
        
        // Service critical
        Pattern.compile(".*service.*unavailable.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*application.*shutdown.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*circuit.*breaker.*open.*", Pattern.CASE_INSENSITIVE),
        
        // StackOverflow
        Pattern.compile(".*StackOverflowError.*")
    );
    
    // Built-in error patterns for common issues
    private static final List<Pattern> BUILTIN_ERROR_PATTERNS = Arrays.asList(
        // Connection errors
        Pattern.compile(".*Connection refused.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*Connection reset.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*Connection timed out.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*SocketException.*"),
        Pattern.compile(".*SocketTimeoutException.*"),
        Pattern.compile(".*ConnectException.*"),
        Pattern.compile(".*UnknownHostException.*"),
        
        // Timeout errors
        Pattern.compile(".*timeout.*exceeded.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*request.*timeout.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*TimeoutException.*"),
        Pattern.compile(".*ReadTimeoutException.*"),
        Pattern.compile(".*WriteTimeoutException.*"),
        
        // Resource errors
        Pattern.compile(".*resource.*exhausted.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*pool.*exhausted.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*queue.*full.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*rate.*limit.*", Pattern.CASE_INSENSITIVE),
        
        // I/O errors
        Pattern.compile(".*IOException.*"),
        Pattern.compile(".*FileNotFoundException.*"),
        Pattern.compile(".*EOFException.*"),
        Pattern.compile(".*AccessDeniedException.*"),
        
        // Database errors
        Pattern.compile(".*SQLException.*"),
        Pattern.compile(".*DataAccessException.*"),
        Pattern.compile(".*TransactionException.*"),
        Pattern.compile(".*OptimisticLockingFailureException.*"),
        Pattern.compile(".*DeadlockLoserDataAccessException.*"),
        Pattern.compile(".*constraint.*violation.*", Pattern.CASE_INSENSITIVE),
        
        // HTTP errors
        Pattern.compile(".*HTTP.*[45]\\d{2}.*"),
        Pattern.compile(".*status.*code.*[45]\\d{2}.*", Pattern.CASE_INSENSITIVE),
        
        // Authentication errors
        Pattern.compile(".*authentication.*failed.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*AuthenticationException.*"),
        Pattern.compile(".*InvalidCredentialsException.*"),
        Pattern.compile(".*access.*denied.*", Pattern.CASE_INSENSITIVE)
    );
    
    // Built-in warning patterns
    private static final List<Pattern> BUILTIN_WARNING_PATTERNS = Arrays.asList(
        // Performance warnings
        Pattern.compile(".*slow.*query.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*performance.*degraded.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*high.*cpu.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*high.*memory.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*latency.*high.*", Pattern.CASE_INSENSITIVE),
        
        // Deprecation warnings
        Pattern.compile(".*deprecated.*", Pattern.CASE_INSENSITIVE),
        
        // Resource warnings
        Pattern.compile(".*memory.*usage.*high.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*disk.*usage.*high.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*pool.*near.*capacity.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*approaching.*limit.*", Pattern.CASE_INSENSITIVE),
        
        // Retry warnings
        Pattern.compile(".*retry.*attempt.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*retrying.*", Pattern.CASE_INSENSITIVE),
        
        // Configuration warnings
        Pattern.compile(".*configuration.*missing.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*using.*default.*", Pattern.CASE_INSENSITIVE)
    );
    
    // Context lines to capture before/after an issue
    private final int contextLinesBefore;
    private final int contextLinesAfter;
    private final boolean enableDeduplication;
    private final boolean parseJsonLogs;
    
    public LogParser(DashboardConfig config) {
        this.exceptionPatterns = compilePatterns(config.getExceptionPatterns());
        this.errorPatterns = compilePatterns(config.getErrorPatterns());
        this.warningPatterns = compilePatterns(config.getWarningPatterns());
        this.exclusionPatterns = compilePatterns(
            config.getExclusionPatterns() != null ? config.getExclusionPatterns() : new ArrayList<>()
        );
        
        // Compile critical patterns from config or use defaults
        List<String> configCriticalPatterns = config.getCriticalPatterns();
        if (configCriticalPatterns != null && !configCriticalPatterns.isEmpty()) {
            this.criticalPatterns = compilePatterns(configCriticalPatterns);
        } else {
            this.criticalPatterns = new ArrayList<>(BUILTIN_CRITICAL_PATTERNS);
        }
        
        // Load custom rules from config
        this.customRules = loadCustomRules(config);
        
        // Configuration for context capture and deduplication
        this.contextLinesBefore = config.getContextLinesBefore();
        this.contextLinesAfter = config.getContextLinesAfter();
        this.enableDeduplication = config.isEnableDeduplication();
        this.parseJsonLogs = config.isParseJsonLogs();
        
        // Periodically clean up old fingerprints
        startFingerprintCleanup();
    }
    
    private List<Pattern> compilePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                compiled.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                System.err.println("Invalid pattern: " + pattern + " - " + e.getMessage());
            }
        }
        return compiled;
    }
    
    private List<CustomRule> loadCustomRules(DashboardConfig config) {
        List<CustomRule> rules = new ArrayList<>();
        List<Map<String, Object>> configRules = config.getCustomRules();
        
        if (configRules != null) {
            for (Map<String, Object> ruleConfig : configRules) {
                try {
                    CustomRule rule = new CustomRule();
                    rule.name = (String) ruleConfig.get("name");
                    rule.pattern = Pattern.compile((String) ruleConfig.get("pattern"), Pattern.CASE_INSENSITIVE);
                    String severityStr = (String) ruleConfig.getOrDefault("severity", "ERROR");
                    rule.severity = Severity.valueOf(severityStr.toUpperCase());
                    rule.issueType = (String) ruleConfig.getOrDefault("issueType", rule.name);
                    rule.extractGroup = (Integer) ruleConfig.getOrDefault("extractGroup", 0);
                    rules.add(rule);
                } catch (Exception e) {
                    System.err.println("Invalid custom rule: " + ruleConfig + " - " + e.getMessage());
                }
            }
        }
        
        return rules;
    }
    
    private void startFingerprintCleanup() {
        // Clean up old fingerprints every 30 seconds
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30000);
                    long cutoff = System.currentTimeMillis() - (DEDUP_WINDOW_MS * 2);
                    recentIssueFingerprints.entrySet().removeIf(e -> e.getValue() < cutoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "LogParser-Cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
    
    /**
     * Parses new lines from a log file and returns detected issues.
     * Enhanced version with context capture, JSON parsing, and deduplication.
     */
    public List<LogIssue> parseLines(String serverName, String fileName, List<String> lines, int startLineNumber) {
        List<LogIssue> issues = new ArrayList<>();
        
        int lineNum = startLineNumber;
        int i = 0;
        
        while (i < lines.size()) {
            String line = lines.get(i);
            
            // Skip empty lines
            if (line.trim().isEmpty()) {
                lineNum++;
                i++;
                continue;
            }
            
            // Check exclusion patterns first - skip false positives
            if (matchesAny(line, exclusionPatterns)) {
                lineNum++;
                i++;
                continue;
            }
            
            // Try to parse as JSON log if enabled
            if (parseJsonLogs && isJsonLog(line)) {
                LogIssue jsonIssue = parseJsonLogLine(serverName, fileName, lineNum, line);
                if (jsonIssue != null) {
                    if (!isDuplicate(jsonIssue)) {
                        issues.add(jsonIssue);
                    }
                    lineNum++;
                    i++;
                    continue;
                }
            }
            
            // Check custom rules first
            LogIssue customIssue = checkCustomRules(serverName, fileName, lineNum, line);
            if (customIssue != null) {
                if (!isDuplicate(customIssue)) {
                    issues.add(customIssue);
                }
                lineNum++;
                i++;
                continue;
            }
            
            // Check for CRITICAL patterns (highest priority)
            if (matchesAny(line, criticalPatterns)) {
                StringBuilder context = new StringBuilder(line);
                int issueLineNum = lineNum;
                
                // Capture context lines after
                int j = i + 1;
                int contextCaptured = 0;
                while (j < lines.size() && contextCaptured < contextLinesAfter) {
                    String nextLine = lines.get(j);
                    if (isStackTraceLine(nextLine) || !TIMESTAMP_PATTERN.matcher(nextLine).find()) {
                        context.append("\n").append(nextLine);
                        j++;
                        // Stack trace lines don't count toward context limit
                        if (!isStackTraceLine(nextLine)) {
                            contextCaptured++;
                        }
                    } else {
                        break;
                    }
                }
                
                String issueType = extractIssueType(line, "CRITICAL");
                String message = extractMessage(line);
                
                LogIssue issue = new LogIssue(
                    serverName, fileName, issueLineNum, issueType, message,
                    addContextBefore(lines, i, context.toString()),
                    Severity.CRITICAL
                );
                
                if (!isDuplicate(issue)) {
                    issues.add(issue);
                }
                
                lineNum += (j - i);
                i = j;
                continue;
            }
            
            // Check for exception patterns
            if (matchesAny(line, exceptionPatterns)) {
                StringBuilder stackTrace = new StringBuilder(line);
                int exceptionLineNum = lineNum;
                
                // Collect subsequent stack trace lines
                int j = i + 1;
                while (j < lines.size() && isStackTraceLine(lines.get(j))) {
                    stackTrace.append("\n").append(lines.get(j));
                    j++;
                }
                
                String issueType = extractExceptionType(line);
                String message = extractMessage(line);
                
                // Determine if this should be elevated to CRITICAL
                Severity severity = Severity.EXCEPTION;
                if (isCriticalException(line, stackTrace.toString())) {
                    severity = Severity.CRITICAL;
                }
                
                LogIssue issue = new LogIssue(
                    serverName, fileName, exceptionLineNum, issueType, message,
                    addContextBefore(lines, i, stackTrace.toString()),
                    severity
                );
                
                if (!isDuplicate(issue)) {
                    issues.add(issue);
                }
                
                lineNum += (j - i);
                i = j;
                continue;
            }
            
            // Check for error patterns (including built-in)
            if (matchesAny(line, errorPatterns) || matchesAny(line, BUILTIN_ERROR_PATTERNS)) {
                String message = line.trim();
                String issueType = extractIssueType(line, "ERROR");
                
                LogIssue issue = new LogIssue(
                    serverName, fileName, lineNum, issueType, message,
                    addContextBefore(lines, i, line),
                    Severity.ERROR
                );
                
                if (!isDuplicate(issue)) {
                    issues.add(issue);
                }
            }
            // Check for warning patterns (including built-in)
            else if (matchesAny(line, warningPatterns) || matchesAny(line, BUILTIN_WARNING_PATTERNS)) {
                String message = line.trim();
                String issueType = extractIssueType(line, "WARNING");
                
                LogIssue issue = new LogIssue(
                    serverName, fileName, lineNum, issueType, message,
                    addContextBefore(lines, i, line),
                    Severity.WARNING
                );
                
                if (!isDuplicate(issue)) {
                    issues.add(issue);
                }
            }
            
            lineNum++;
            i++;
        }
        
        return issues;
    }
    
    /**
     * Check if a line contains a JSON log entry.
     */
    private boolean isJsonLog(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }
    
    /**
     * Parse a JSON log line and extract issue information.
     */
    private LogIssue parseJsonLogLine(String serverName, String fileName, int lineNum, String line) {
        try {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            
            // Extract log level
            String level = extractJsonField(json, "level", "severity", "log_level", "loglevel");
            if (level == null) return null;
            
            level = level.toUpperCase();
            
            // Determine severity
            Severity severity;
            switch (level) {
                case "FATAL":
                case "CRITICAL":
                case "SEVERE":
                    severity = Severity.CRITICAL;
                    break;
                case "ERROR":
                    severity = Severity.ERROR;
                    break;
                case "WARN":
                case "WARNING":
                    severity = Severity.WARNING;
                    break;
                default:
                    return null; // Skip DEBUG, INFO, TRACE
            }
            
            // Extract message
            String message = extractJsonField(json, "message", "msg", "error", "exception");
            if (message == null) message = line;
            
            // Extract exception/error type
            String issueType = extractJsonField(json, "exception", "error_type", "exception_class", "type");
            if (issueType == null) issueType = severity.name();
            
            // Extract stack trace if available
            String stackTrace = extractJsonField(json, "stacktrace", "stack_trace", "stack", "trace");
            if (stackTrace == null) stackTrace = line;
            
            return new LogIssue(serverName, fileName, lineNum, issueType, message, stackTrace, severity);
            
        } catch (Exception e) {
            // Not valid JSON or parsing error
            return null;
        }
    }
    
    /**
     * Extract a field from JSON trying multiple possible field names.
     */
    private String extractJsonField(JsonObject json, String... fieldNames) {
        for (String name : fieldNames) {
            if (json.has(name)) {
                JsonElement element = json.get(name);
                if (!element.isJsonNull()) {
                    return element.isJsonPrimitive() ? element.getAsString() : element.toString();
                }
            }
        }
        return null;
    }
    
    /**
     * Check custom rules for a match.
     */
    private LogIssue checkCustomRules(String serverName, String fileName, int lineNum, String line) {
        for (CustomRule rule : customRules) {
            Matcher matcher = rule.pattern.matcher(line);
            if (matcher.find()) {
                String message = rule.extractGroup > 0 && rule.extractGroup <= matcher.groupCount()
                    ? matcher.group(rule.extractGroup)
                    : line.trim();
                
                return new LogIssue(
                    serverName, fileName, lineNum, rule.issueType, message, line, rule.severity
                );
            }
        }
        return null;
    }
    
    /**
     * Add context lines before the issue.
     */
    private String addContextBefore(List<String> lines, int currentIndex, String currentContent) {
        if (contextLinesBefore <= 0) {
            return currentContent;
        }
        
        StringBuilder context = new StringBuilder();
        int startIndex = Math.max(0, currentIndex - contextLinesBefore);
        
        for (int i = startIndex; i < currentIndex; i++) {
            context.append(lines.get(i)).append("\n");
        }
        
        context.append(currentContent);
        return context.toString();
    }
    
    /**
     * Check if an exception should be elevated to CRITICAL severity.
     */
    private boolean isCriticalException(String line, String stackTrace) {
        String combined = line + "\n" + stackTrace;
        
        // Check for OutOfMemory
        if (combined.contains("OutOfMemoryError") || combined.contains("OutOfMemory")) {
            return true;
        }
        
        // Check for StackOverflow
        if (combined.contains("StackOverflowError")) {
            return true;
        }
        
        // Check for system-level errors
        if (combined.contains("VirtualMachineError") || combined.contains("InternalError")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if this issue is a duplicate of a recent one.
     */
    private boolean isDuplicate(LogIssue issue) {
        if (!enableDeduplication) {
            return false;
        }
        
        // Create fingerprint from key characteristics
        String fingerprint = createFingerprint(issue);
        long now = System.currentTimeMillis();
        
        Long lastSeen = recentIssueFingerprints.get(fingerprint);
        if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
            // Update timestamp but consider it a duplicate
            recentIssueFingerprints.put(fingerprint, now);
            return true;
        }
        
        recentIssueFingerprints.put(fingerprint, now);
        return false;
    }
    
    /**
     * Create a fingerprint for deduplication.
     */
    private String createFingerprint(LogIssue issue) {
        // Normalize message by removing variable parts
        String normalizedMessage = issue.getMessage()
            .replaceAll("\\d{4}[-/]\\d{2}[-/]\\d{2}", "DATE")
            .replaceAll("\\d{2}:\\d{2}:\\d{2}[.,]?\\d*", "TIME")
            .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "UUID")
            .replaceAll("\\b\\d+\\b", "N")
            .replaceAll("0x[0-9a-fA-F]+", "HEX");
        
        return String.format("%s|%s|%s|%s",
            issue.getServerName(),
            issue.getFileName(),
            issue.getIssueType(),
            normalizedMessage.hashCode()
        );
    }
    
    /**
     * Parses new lines from a log file without server name (backward compatible).
     */
    public List<LogIssue> parseLines(String fileName, List<String> lines, int startLineNumber) {
        return parseLines(null, fileName, lines, startLineNumber);
    }
    
    private boolean matchesAny(String line, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isStackTraceLine(String line) {
        return STACK_TRACE_PATTERN.matcher(line).matches() || 
               CAUSED_BY_PATTERN.matcher(line).matches() ||
               MORE_PATTERN.matcher(line).matches() ||
               line.trim().startsWith("... ");
    }
    
    /**
     * Extracts the exception type from a line like "java.lang.NullPointerException: message"
     */
    private String extractExceptionType(String line) {
        // Try to find exception class name
        Pattern exTypePattern = Pattern.compile("([\\w.$]+(?:Exception|Error|Throwable))");
        Matcher matcher = exTypePattern.matcher(line);
        if (matcher.find()) {
            String fullType = matcher.group(1);
            // Return just the class name without package
            int lastDot = fullType.lastIndexOf('.');
            return lastDot >= 0 ? fullType.substring(lastDot + 1) : fullType;
        }
        return "Exception";
    }
    
    /**
     * Extracts issue type from line or returns default.
     */
    private String extractIssueType(String line, String defaultType) {
        // Try to find a specific exception/error type
        String extracted = extractExceptionType(line);
        if (!extracted.equals("Exception")) {
            return extracted;
        }
        
        // Check for common patterns
        if (line.toLowerCase().contains("timeout")) return "Timeout";
        if (line.toLowerCase().contains("connection")) return "Connection";
        if (line.toLowerCase().contains("authentication")) return "Authentication";
        if (line.toLowerCase().contains("permission") || line.toLowerCase().contains("denied")) return "Permission";
        if (line.toLowerCase().contains("memory")) return "Memory";
        if (line.toLowerCase().contains("disk") || line.toLowerCase().contains("storage")) return "Storage";
        
        return defaultType;
    }
    
    /**
     * Extracts the message portion from an exception line.
     */
    private String extractMessage(String line) {
        // Try to extract message after the exception type
        int colonIndex = line.indexOf(':');
        if (colonIndex > 0 && colonIndex < line.length() - 1) {
            String afterColon = line.substring(colonIndex + 1).trim();
            // Check if there's another colon (for nested messages)
            int secondColon = afterColon.indexOf(':');
            if (secondColon > 0) {
                return afterColon.substring(secondColon + 1).trim();
            }
            return afterColon.isEmpty() ? line.trim() : afterColon;
        }
        return line.trim();
    }
    
    /**
     * Custom rule definition.
     */
    private static class CustomRule {
        String name;
        Pattern pattern;
        Severity severity;
        String issueType;
        int extractGroup;
    }
}
