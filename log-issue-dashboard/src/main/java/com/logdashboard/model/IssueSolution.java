package com.logdashboard.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Represents a user-provided solution for a type of issue.
 * Solutions are matched to issues based on patterns and can be upvoted/downvoted.
 */
public class IssueSolution {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private String id;
    private String issuePattern;      // Pattern to match issue type (e.g., "NullPointerException")
    private String messagePattern;    // Optional: regex pattern for message matching
    private String stackPattern;      // Optional: pattern for stack trace matching
    private String solutionTitle;     // Brief title for the solution
    private String solutionDescription; // Detailed solution (supports markdown)
    private String createdBy;         // User identifier (IP or username)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int upvotes;
    private int downvotes;
    private int usageCount;           // How many times this solution was suggested
    private Status status;
    
    public enum Status {
        ACTIVE,
        ARCHIVED,
        DEPRECATED
    }
    
    /**
     * Creates a new solution with generated ID and current timestamp.
     */
    public IssueSolution(String issuePattern, String solutionTitle, String solutionDescription) {
        this.id = UUID.randomUUID().toString();
        this.issuePattern = issuePattern;
        this.solutionTitle = solutionTitle;
        this.solutionDescription = solutionDescription;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.upvotes = 0;
        this.downvotes = 0;
        this.usageCount = 0;
        this.status = Status.ACTIVE;
    }
    
    /**
     * Constructor for restoring from database.
     */
    public IssueSolution(String id, String issuePattern, String messagePattern, String stackPattern,
                         String solutionTitle, String solutionDescription, String createdBy,
                         LocalDateTime createdAt, LocalDateTime updatedAt,
                         int upvotes, int downvotes, int usageCount, Status status) {
        this.id = id;
        this.issuePattern = issuePattern;
        this.messagePattern = messagePattern;
        this.stackPattern = stackPattern;
        this.solutionTitle = solutionTitle;
        this.solutionDescription = solutionDescription;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.upvotes = upvotes;
        this.downvotes = downvotes;
        this.usageCount = usageCount;
        this.status = status;
    }
    
    // Getters
    public String getId() { return id; }
    public String getIssuePattern() { return issuePattern; }
    public String getMessagePattern() { return messagePattern; }
    public String getStackPattern() { return stackPattern; }
    public String getSolutionTitle() { return solutionTitle; }
    public String getSolutionDescription() { return solutionDescription; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public int getUpvotes() { return upvotes; }
    public int getDownvotes() { return downvotes; }
    public int getUsageCount() { return usageCount; }
    public Status getStatus() { return status; }
    
    public String getFormattedCreatedAt() {
        return createdAt != null ? createdAt.format(FORMATTER) : "";
    }
    
    public String getFormattedUpdatedAt() {
        return updatedAt != null ? updatedAt.format(FORMATTER) : "";
    }
    
    /**
     * Calculates a rating score based on votes (0-5 scale).
     */
    public double getRating() {
        int total = upvotes + downvotes;
        if (total == 0) return 0;
        return Math.round((double) upvotes / total * 5 * 10) / 10.0;
    }
    
    /**
     * Gets the net score (upvotes - downvotes).
     */
    public int getNetScore() {
        return upvotes - downvotes;
    }
    
    // Setters
    public void setId(String id) { this.id = id; }
    public void setIssuePattern(String issuePattern) { this.issuePattern = issuePattern; }
    public void setMessagePattern(String messagePattern) { this.messagePattern = messagePattern; }
    public void setStackPattern(String stackPattern) { this.stackPattern = stackPattern; }
    public void setSolutionTitle(String solutionTitle) { this.solutionTitle = solutionTitle; }
    public void setSolutionDescription(String solutionDescription) { this.solutionDescription = solutionDescription; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public void setUpvotes(int upvotes) { this.upvotes = upvotes; }
    public void setDownvotes(int downvotes) { this.downvotes = downvotes; }
    public void setUsageCount(int usageCount) { this.usageCount = usageCount; }
    public void setStatus(Status status) { this.status = status; }
    
    public void incrementUpvotes() { this.upvotes++; }
    public void incrementDownvotes() { this.downvotes++; }
    public void incrementUsageCount() { this.usageCount++; }
    
    @Override
    public String toString() {
        return String.format("Solution[%s]: %s (pattern: %s, votes: +%d/-%d)", 
            id, solutionTitle, issuePattern, upvotes, downvotes);
    }
}
