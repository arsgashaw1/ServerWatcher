package com.logdashboard.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a detected issue/exception from a log file.
 */
public class LogIssue {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final String id;
    private final String serverName;
    private final String fileName;
    private final int lineNumber;
    private final String issueType;
    private final String message;
    private final String fullStackTrace;
    private final LocalDateTime detectedAt;
    private final Severity severity;
    private boolean acknowledged;

    public enum Severity {
        ERROR("Error", "#FF4444"),
        WARNING("Warning", "#FFAA00"),
        EXCEPTION("Exception", "#FF0000"),
        CRITICAL("Critical", "#8B0000");

        private final String displayName;
        private final String color;

        Severity(String displayName, String color) {
            this.displayName = displayName;
            this.color = color;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getColor() {
            return color;
        }
    }

    public LogIssue(String serverName, String fileName, int lineNumber, String issueType, 
                    String message, String fullStackTrace, Severity severity) {
        this.id = generateId();
        this.serverName = serverName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.issueType = issueType;
        this.message = message;
        this.fullStackTrace = fullStackTrace;
        this.detectedAt = LocalDateTime.now();
        this.severity = severity;
        this.acknowledged = false;
    }
    
    /**
     * Constructor without server name (for backward compatibility).
     */
    public LogIssue(String fileName, int lineNumber, String issueType, String message, 
                    String fullStackTrace, Severity severity) {
        this(null, fileName, lineNumber, issueType, message, fullStackTrace, severity);
    }

    private String generateId() {
        return String.valueOf(System.nanoTime());
    }

    public String getId() {
        return id;
    }

    public String getServerName() {
        return serverName;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getIssueType() {
        return issueType;
    }

    public String getMessage() {
        return message;
    }

    public String getFullStackTrace() {
        return fullStackTrace;
    }

    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }

    public String getFormattedTime() {
        return detectedAt.format(FORMATTER);
    }

    public Severity getSeverity() {
        return severity;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    @Override
    public String toString() {
        String serverInfo = serverName != null ? serverName + ":" : "";
        return String.format("[%s] %s - %s%s:%d - %s", 
            severity.getDisplayName(), getFormattedTime(), serverInfo, fileName, lineNumber, message);
    }
}
