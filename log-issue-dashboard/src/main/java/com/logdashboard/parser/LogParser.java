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
    
    private final PatternSet exceptionPatterns;
    private final PatternSet errorPatterns;     // Configured + built-in error patterns
    private final PatternSet warningPatterns;   // Configured + built-in warning patterns
    private final PatternSet exclusionPatterns;
    private final PatternSet criticalPatterns;
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

    // Class name suffixes recognized when extracting exception types
    private static final String[] EXCEPTION_SUFFIXES = {"Exception", "Error", "Throwable"};

    // Patterns used to normalize messages for deduplication fingerprints
    private static final Pattern FP_DATE = Pattern.compile("\\d{4}[-/]\\d{2}[-/]\\d{2}");
    private static final Pattern FP_TIME = Pattern.compile("\\d{2}:\\d{2}:\\d{2}[.,]?\\d*");
    private static final Pattern FP_UUID =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern FP_NUMBER = Pattern.compile("\\b\\d+\\b");
    private static final Pattern FP_HEX = Pattern.compile("0x[0-9a-fA-F]+");
    
    // Built-in critical patterns for severe issues
    private static final List<Pattern> BUILTIN_CRITICAL_PATTERNS = Arrays.asList(
        // OutOfMemory errors
        compileForFind(".*OutOfMemory.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*java\\.lang\\.OutOfMemoryError.*"),
        compileForFind(".*GC overhead limit exceeded.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*Java heap space.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*unable to create new native thread.*", Pattern.CASE_INSENSITIVE),
        
        // Thread/Deadlock issues
        compileForFind(".*deadlock.*detected.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*ThreadDeath.*"),
        compileForFind(".*DEADLOCK.*", Pattern.CASE_INSENSITIVE),
        
        // System crashes
        compileForFind(".*FATAL.*ERROR.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*system.*crash.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*JVM.*crash.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*core\\s+dumped.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*Segmentation fault.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*SIGSEGV.*"),
        compileForFind(".*SIGKILL.*"),
        compileForFind(".*SIGABRT.*"),
        
        // Database critical
        compileForFind(".*database.*connection.*lost.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*too many connections.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*tablespace.*full.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*disk.*full.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*no space left.*", Pattern.CASE_INSENSITIVE),
        
        // Security critical
        compileForFind(".*authentication.*fail.*multiple.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*unauthorized.*access.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*SSL.*handshake.*fail.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*certificate.*expired.*", Pattern.CASE_INSENSITIVE),
        
        // Service critical
        compileForFind(".*service.*unavailable.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*application.*shutdown.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*circuit.*breaker.*open.*", Pattern.CASE_INSENSITIVE),
        
        // StackOverflow
        compileForFind(".*StackOverflowError.*")
    );
    
    // Built-in error patterns for common issues
    private static final List<Pattern> BUILTIN_ERROR_PATTERNS = Arrays.asList(
        // Connection errors
        compileForFind(".*Connection refused.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*Connection reset.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*Connection timed out.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*SocketException.*"),
        compileForFind(".*SocketTimeoutException.*"),
        compileForFind(".*ConnectException.*"),
        compileForFind(".*UnknownHostException.*"),
        
        // Timeout errors
        compileForFind(".*timeout.*exceeded.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*request.*timeout.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*TimeoutException.*"),
        compileForFind(".*ReadTimeoutException.*"),
        compileForFind(".*WriteTimeoutException.*"),
        
        // Resource errors
        compileForFind(".*resource.*exhausted.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*pool.*exhausted.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*queue.*full.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*rate.*limit.*", Pattern.CASE_INSENSITIVE),
        
        // I/O errors
        compileForFind(".*IOException.*"),
        compileForFind(".*FileNotFoundException.*"),
        compileForFind(".*EOFException.*"),
        compileForFind(".*AccessDeniedException.*"),
        
        // Database errors
        compileForFind(".*SQLException.*"),
        compileForFind(".*DataAccessException.*"),
        compileForFind(".*TransactionException.*"),
        compileForFind(".*OptimisticLockingFailureException.*"),
        compileForFind(".*DeadlockLoserDataAccessException.*"),
        compileForFind(".*constraint.*violation.*", Pattern.CASE_INSENSITIVE),
        
        // HTTP errors
        compileForFind(".*HTTP.*[45]\\d{2}.*"),
        compileForFind(".*status.*code.*[45]\\d{2}.*", Pattern.CASE_INSENSITIVE),
        
        // Authentication errors
        compileForFind(".*authentication.*failed.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*AuthenticationException.*"),
        compileForFind(".*InvalidCredentialsException.*"),
        compileForFind(".*access.*denied.*", Pattern.CASE_INSENSITIVE)
    );
    
    // Built-in warning patterns
    private static final List<Pattern> BUILTIN_WARNING_PATTERNS = Arrays.asList(
        // Performance warnings
        compileForFind(".*slow.*query.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*performance.*degraded.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*high.*cpu.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*high.*memory.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*latency.*high.*", Pattern.CASE_INSENSITIVE),
        
        // Deprecation warnings
        compileForFind(".*deprecated.*", Pattern.CASE_INSENSITIVE),
        
        // Resource warnings
        compileForFind(".*memory.*usage.*high.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*disk.*usage.*high.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*pool.*near.*capacity.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*approaching.*limit.*", Pattern.CASE_INSENSITIVE),
        
        // Retry warnings
        compileForFind(".*retry.*attempt.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*retrying.*", Pattern.CASE_INSENSITIVE),
        
        // Configuration warnings
        compileForFind(".*configuration.*missing.*", Pattern.CASE_INSENSITIVE),
        compileForFind(".*using.*default.*", Pattern.CASE_INSENSITIVE)
    );
    
    // Context lines to capture before/after an issue
    private final int contextLinesBefore;
    private final int contextLinesAfter;
    private final boolean enableDeduplication;
    private final boolean parseJsonLogs;
    
    public LogParser(DashboardConfig config) {
        this.exceptionPatterns = PatternSet.of(compilePatterns(config.getExceptionPatterns()));
        this.errorPatterns = PatternSet.of(compilePatterns(config.getErrorPatterns()), BUILTIN_ERROR_PATTERNS);
        this.warningPatterns = PatternSet.of(compilePatterns(config.getWarningPatterns()), BUILTIN_WARNING_PATTERNS);
        this.exclusionPatterns = PatternSet.of(compilePatterns(config.getExclusionPatterns()));
        
        // Compile critical patterns from config or use defaults
        List<String> configCriticalPatterns = config.getCriticalPatterns();
        if (configCriticalPatterns != null && !configCriticalPatterns.isEmpty()) {
            this.criticalPatterns = PatternSet.of(compilePatterns(configCriticalPatterns));
        } else {
            this.criticalPatterns = PatternSet.of(BUILTIN_CRITICAL_PATTERNS);
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
        if (patterns == null) {
            return compiled;
        }
        for (String pattern : patterns) {
            try {
                compiled.add(compileForFind(pattern, Pattern.CASE_INSENSITIVE));
            } catch (Exception e) {
                System.err.println("Invalid pattern: " + pattern + " - " + e.getMessage());
            }
        }
        return compiled;
    }

    /**
     * Compiles a pattern that will only be used with {@link Matcher#find()}.
     *
     * Detection patterns are conventionally written as ".*Foo.*". With find() the
     * surrounding ".*" are redundant, but they make every non-matching line cost
     * O(n^2) backtracking per pattern. Since almost every log line goes through
     * ~90 patterns, stripping them is the single largest CPU saving in parsing.
     * The match result is identical; falls back to the original if stripping
     * yields an invalid expression.
     */
    static Pattern compileForFind(String regex) {
        return compileForFind(regex, 0);
    }

    static Pattern compileForFind(String regex, int flags) {
        String stripped = stripRedundantWildcards(regex);
        if (!stripped.equals(regex)) {
            try {
                return Pattern.compile(stripped, flags);
            } catch (java.util.regex.PatternSyntaxException e) {
                // Fall through to the original expression
            }
        }
        return Pattern.compile(regex, flags);
    }

    static String stripRedundantWildcards(String regex) {
        if (regex == null || regex.contains("\\Q")) {
            return regex;
        }
        String result = regex;
        if (result.startsWith(".*?")) {
            result = result.substring(3);
        } else if (result.startsWith(".*") && !result.startsWith(".*+")) {
            result = result.substring(2);
        }
        if (result.endsWith(".*?") && !isEscaped(result, result.length() - 3)) {
            result = result.substring(0, result.length() - 3);
        } else if (result.endsWith(".*") && !isEscaped(result, result.length() - 2)) {
            result = result.substring(0, result.length() - 2);
        }
        return result;
    }

    /** Returns true if the character at index is preceded by an odd number of backslashes. */
    private static boolean isEscaped(String s, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }
    
    private List<CustomRule> loadCustomRules(DashboardConfig config) {
        List<CustomRule> rules = new ArrayList<>();
        List<Map<String, Object>> configRules = config.getCustomRules();
        
        if (configRules != null) {
            for (Map<String, Object> ruleConfig : configRules) {
                try {
                    CustomRule rule = new CustomRule();
                    rule.name = (String) ruleConfig.get("name");
                    rule.pattern = compileForFind((String) ruleConfig.get("pattern"), Pattern.CASE_INSENSITIVE);
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
            
            // Lowercased once per line for the literal prefilters of every pattern set
            String lowerLine = asciiLowerCase(line);

            // Check exclusion patterns first - skip false positives
            if (exclusionPatterns.matchesAny(line, lowerLine)) {
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
            if (criticalPatterns.matchesAny(line, lowerLine)) {
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
            if (exceptionPatterns.matchesAny(line, lowerLine)) {
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
            if (errorPatterns.matchesAny(line, lowerLine)) {
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
            else if (warningPatterns.matchesAny(line, lowerLine)) {
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
        String normalizedMessage = issue.getMessage();
        normalizedMessage = FP_DATE.matcher(normalizedMessage).replaceAll("DATE");
        normalizedMessage = FP_TIME.matcher(normalizedMessage).replaceAll("TIME");
        normalizedMessage = FP_UUID.matcher(normalizedMessage).replaceAll("UUID");
        normalizedMessage = FP_NUMBER.matcher(normalizedMessage).replaceAll("N");
        normalizedMessage = FP_HEX.matcher(normalizedMessage).replaceAll("HEX");
        
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
    
    /**
     * A group of patterns checked with find(), each paired with the literal substrings any
     * match must contain. A cheap indexOf on the lowercased line rules out most patterns
     * before the regex engine runs - important because nearly every log line is noise that
     * would otherwise be scanned position-by-position by ~90 regexes.
     */
    private static final class PatternSet {
        private final Pattern[] patterns;
        private final String[][] requiredLiterals;

        @SafeVarargs
        static PatternSet of(List<Pattern>... groups) {
            List<Pattern> all = new ArrayList<>();
            for (List<Pattern> group : groups) {
                all.addAll(group);
            }
            return new PatternSet(all);
        }

        private PatternSet(List<Pattern> patterns) {
            this.patterns = patterns.toArray(new Pattern[0]);
            this.requiredLiterals = new String[this.patterns.length][];
            for (int i = 0; i < this.patterns.length; i++) {
                requiredLiterals[i] = requiredLiterals(this.patterns[i].pattern());
            }
        }

        boolean matchesAny(String line, String lowerLine) {
            for (int i = 0; i < patterns.length; i++) {
                String[] literals = requiredLiterals[i];
                if (literals != null && !containsAll(lowerLine, literals)) {
                    continue;
                }
                if (patterns[i].matcher(line).find()) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsAll(String text, String[] literals) {
            for (String literal : literals) {
                if (text.indexOf(literal) < 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Extracts lowercase ASCII literal runs (length >= 2) that every match of the regex must
     * contain. Returns null when the expression uses constructs this conservative scanner does
     * not model (groups, alternation, unusual escapes), in which case no prefilter is applied.
     * Only ASCII is used so that ASCII lowercasing of the line is an exact necessary condition
     * for both case-sensitive and (ASCII) case-insensitive patterns.
     */
    static String[] requiredLiterals(String regex) {
        List<String> literals = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        boolean lastWasLiteral = false;
        int length = regex.length();
        int i = 0;
        while (i < length) {
            char c = regex.charAt(i);
            switch (c) {
                case '|':
                case '(':
                case ')':
                    return null;
                case '*':
                case '?':
                case '{':
                    // The preceding literal is optional or repeated a variable number of times
                    if (lastWasLiteral) {
                        run.setLength(run.length() - 1);
                    }
                    flushLiteral(run, literals);
                    if (c == '{') {
                        int close = regex.indexOf('}', i);
                        if (close < 0) {
                            return null;
                        }
                        i = close;
                    }
                    i = skipQuantifierModifier(regex, i + 1);
                    lastWasLiteral = false;
                    break;
                case '+':
                    // At least one occurrence: the preceding literal stays required
                    flushLiteral(run, literals);
                    i = skipQuantifierModifier(regex, i + 1);
                    lastWasLiteral = false;
                    break;
                case '[': {
                    flushLiteral(run, literals);
                    int j = i + 1;
                    if (j < length && regex.charAt(j) == '^') j++;
                    if (j < length && regex.charAt(j) == ']') j++;
                    while (j < length && regex.charAt(j) != ']') {
                        char classChar = regex.charAt(j);
                        if (classChar == '[') {
                            return null;
                        }
                        if (classChar == '\\') {
                            j++;
                        }
                        j++;
                    }
                    if (j >= length) {
                        return null;
                    }
                    i = j + 1;
                    lastWasLiteral = false;
                    break;
                }
                case '\\': {
                    if (i + 1 >= length) {
                        return null;
                    }
                    char escaped = regex.charAt(i + 1);
                    if (escaped > ' ' && escaped < 128 && !Character.isLetterOrDigit(escaped)) {
                        run.append(escaped);
                        lastWasLiteral = true;
                    } else if ("bBdDsSwWAzZGhHvVRtnrfae".indexOf(escaped) >= 0) {
                        flushLiteral(run, literals);
                        lastWasLiteral = false;
                    } else {
                        return null;  // Quoting, properties, hex/unicode escapes, back-references, ...
                    }
                    i += 2;
                    break;
                }
                case '.':
                case '^':
                case '$':
                case ']':
                case '}':
                    flushLiteral(run, literals);
                    lastWasLiteral = false;
                    i++;
                    break;
                default:
                    if (c < 128) {
                        run.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
                        lastWasLiteral = true;
                    } else {
                        flushLiteral(run, literals);
                        lastWasLiteral = false;
                    }
                    i++;
            }
        }
        flushLiteral(run, literals);
        return literals.isEmpty() ? null : literals.toArray(new String[0]);
    }

    private static int skipQuantifierModifier(String regex, int index) {
        if (index < regex.length() && (regex.charAt(index) == '?' || regex.charAt(index) == '+')) {
            return index + 1;
        }
        return index;
    }

    private static void flushLiteral(StringBuilder run, List<String> literals) {
        if (run.length() >= 2) {
            literals.add(run.toString().toLowerCase(Locale.ROOT));
        }
        run.setLength(0);
    }

    /** Lowercases ASCII letters only, preserving length and all other characters. */
    static String asciiLowerCase(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] chars = s.toCharArray();
                for (int j = i; j < chars.length; j++) {
                    if (chars[j] >= 'A' && chars[j] <= 'Z') {
                        chars[j] = (char) (chars[j] + 32);
                    }
                }
                return new String(chars);
            }
        }
        return s;
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
        String fullType = findExceptionClassName(line);
        if (fullType != null) {
            // Return just the class name without package
            int lastDot = fullType.lastIndexOf('.');
            return lastDot >= 0 ? fullType.substring(lastDot + 1) : fullType;
        }
        return "Exception";
    }

    /**
     * Returns what {@code ([\w.$]+(?:Exception|Error|Throwable))} would find, without the
     * regex's heavy backtracking: the first run of identifier characters that contains a
     * suffix preceded by at least one character, cut after the last such suffix (greedy).
     */
    static String findExceptionClassName(String line) {
        int length = line.length();
        int i = 0;
        while (i < length) {
            if (!isTypeNameChar(line.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < length && isTypeNameChar(line.charAt(i))) {
                i++;
            }
            int bestStart = -1;
            int bestEnd = -1;
            for (String suffix : EXCEPTION_SUFFIXES) {
                int index = line.lastIndexOf(suffix, i - suffix.length());
                if (index > start && index > bestStart) {
                    bestStart = index;
                    bestEnd = index + suffix.length();
                }
            }
            if (bestStart > 0) {
                return line.substring(start, bestEnd);
            }
        }
        return null;
    }

    private static boolean isTypeNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || c == '_' || c == '.' || c == '$';
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
        String lower = line.toLowerCase();
        if (lower.contains("timeout")) return "Timeout";
        if (lower.contains("connection")) return "Connection";
        if (lower.contains("authentication")) return "Authentication";
        if (lower.contains("permission") || lower.contains("denied")) return "Permission";
        if (lower.contains("memory")) return "Memory";
        if (lower.contains("disk") || lower.contains("storage")) return "Storage";
        
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
