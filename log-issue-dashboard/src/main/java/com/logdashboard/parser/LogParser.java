package com.logdashboard.parser;

import com.logdashboard.config.DashboardConfig;
import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses log file content and detects issues/exceptions.
 */
public class LogParser {
    
    private final List<Pattern> exceptionPatterns;
    private final List<Pattern> errorPatterns;
    private final List<Pattern> warningPatterns;
    private final List<Pattern> exclusionPatterns;  // False positive filters
    
    // Pattern to detect stack trace elements
    private static final Pattern STACK_TRACE_PATTERN = 
        Pattern.compile("^\\s+at\\s+[\\w.$]+\\([^)]+\\).*$");
    
    // Pattern to detect "Caused by:" lines
    private static final Pattern CAUSED_BY_PATTERN = 
        Pattern.compile("^Caused by:.*$");
    
    public LogParser(DashboardConfig config) {
        this.exceptionPatterns = compilePatterns(config.getExceptionPatterns());
        this.errorPatterns = compilePatterns(config.getErrorPatterns());
        this.warningPatterns = compilePatterns(config.getWarningPatterns());
        this.exclusionPatterns = compilePatterns(
            config.getExclusionPatterns() != null ? config.getExclusionPatterns() : new ArrayList<>()
        );
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
    
    /**
     * Parses new lines from a log file and returns detected issues.
     */
    public List<LogIssue> parseLines(String serverName, String fileName, List<String> lines, int startLineNumber) {
        List<LogIssue> issues = new ArrayList<>();
        
        int lineNum = startLineNumber;
        int i = 0;
        
        while (i < lines.size()) {
            String line = lines.get(i);
            
            // Check exclusion patterns first - skip false positives
            if (matchesAny(line, exclusionPatterns)) {
                lineNum++;
                i++;
                continue;
            }
            
            // Check for exception patterns (highest priority)
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
                
                issues.add(new LogIssue(
                    serverName,
                    fileName,
                    exceptionLineNum,
                    issueType,
                    message,
                    stackTrace.toString(),
                    Severity.EXCEPTION
                ));
                
                // Skip processed stack trace lines
                lineNum += (j - i);
                i = j;
                continue;
            }
            
            // Check for error patterns
            if (matchesAny(line, errorPatterns)) {
                issues.add(new LogIssue(
                    serverName,
                    fileName,
                    lineNum,
                    "ERROR",
                    line.trim(),
                    line,
                    Severity.ERROR
                ));
            }
            // Check for warning patterns
            else if (matchesAny(line, warningPatterns)) {
                issues.add(new LogIssue(
                    serverName,
                    fileName,
                    lineNum,
                    "WARNING",
                    line.trim(),
                    line,
                    Severity.WARNING
                ));
            }
            
            lineNum++;
            i++;
        }
        
        return issues;
    }
    
    /**
     * Parses new lines from a log file without server name (backward compatible).
     */
    public List<LogIssue> parseLines(String fileName, List<String> lines, int startLineNumber) {
        return parseLines(null, fileName, lines, startLineNumber);
    }
    
    private boolean matchesAny(String line, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isStackTraceLine(String line) {
        return STACK_TRACE_PATTERN.matcher(line).matches() || 
               CAUSED_BY_PATTERN.matcher(line).matches() ||
               line.trim().startsWith("... ");
    }
    
    /**
     * Extracts the exception type from a line like "java.lang.NullPointerException: message"
     */
    private String extractExceptionType(String line) {
        // Try to find exception class name
        Pattern exTypePattern = Pattern.compile("([\\w.$]+Exception|[\\w.$]+Error)");
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
}
