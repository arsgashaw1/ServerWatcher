package com.logdashboard.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Represents a match between a solution and an issue occurrence.
 * Used for tracking suggestion effectiveness and user feedback.
 */
public class SolutionMatch {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private String id;
    private String solutionId;
    private String issueId;
    private int matchScore;           // Score indicating match quality (0-100+)
    private LocalDateTime matchedAt;
    private Boolean wasHelpful;       // User feedback: true=helpful, false=not helpful, null=no feedback
    private LocalDateTime feedbackAt;
    
    /**
     * Creates a new solution match.
     */
    public SolutionMatch(String solutionId, String issueId, int matchScore) {
        this.id = UUID.randomUUID().toString();
        this.solutionId = solutionId;
        this.issueId = issueId;
        this.matchScore = matchScore;
        this.matchedAt = LocalDateTime.now();
        this.wasHelpful = null;
        this.feedbackAt = null;
    }
    
    /**
     * Constructor for restoring from database.
     */
    public SolutionMatch(String id, String solutionId, String issueId, int matchScore,
                         LocalDateTime matchedAt, Boolean wasHelpful, LocalDateTime feedbackAt) {
        this.id = id;
        this.solutionId = solutionId;
        this.issueId = issueId;
        this.matchScore = matchScore;
        this.matchedAt = matchedAt;
        this.wasHelpful = wasHelpful;
        this.feedbackAt = feedbackAt;
    }
    
    // Getters
    public String getId() { return id; }
    public String getSolutionId() { return solutionId; }
    public String getIssueId() { return issueId; }
    public int getMatchScore() { return matchScore; }
    public LocalDateTime getMatchedAt() { return matchedAt; }
    public Boolean getWasHelpful() { return wasHelpful; }
    public LocalDateTime getFeedbackAt() { return feedbackAt; }
    
    public String getFormattedMatchedAt() {
        return matchedAt != null ? matchedAt.format(FORMATTER) : "";
    }
    
    public String getFormattedFeedbackAt() {
        return feedbackAt != null ? feedbackAt.format(FORMATTER) : "";
    }
    
    // Setters
    public void setId(String id) { this.id = id; }
    public void setSolutionId(String solutionId) { this.solutionId = solutionId; }
    public void setIssueId(String issueId) { this.issueId = issueId; }
    public void setMatchScore(int matchScore) { this.matchScore = matchScore; }
    public void setMatchedAt(LocalDateTime matchedAt) { this.matchedAt = matchedAt; }
    public void setWasHelpful(Boolean wasHelpful) { this.wasHelpful = wasHelpful; }
    public void setFeedbackAt(LocalDateTime feedbackAt) { this.feedbackAt = feedbackAt; }
    
    /**
     * Records user feedback on whether the suggestion was helpful.
     */
    public void recordFeedback(boolean helpful) {
        this.wasHelpful = helpful;
        this.feedbackAt = LocalDateTime.now();
    }
    
    @Override
    public String toString() {
        return String.format("Match[%s]: solution=%s, issue=%s, score=%d, helpful=%s",
            id, solutionId, issueId, matchScore, wasHelpful);
    }
}
