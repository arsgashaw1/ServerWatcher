package com.logdashboard.store;

import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Interface for issue storage backends.
 * Implementations can be in-memory (IssueStore) or database-backed (H2IssueStore).
 */
public interface IssueRepository {
    
    /**
     * Listener interface for new issue notifications.
     */
    interface IssueListener {
        void onNewIssue(LogIssue issue);
    }
    
    /**
     * Adds a new issue to the store.
     */
    void addIssue(LogIssue issue);
    
    /**
     * Adds a listener for new issues.
     */
    boolean addListener(IssueListener listener);
    
    /**
     * Removes a listener.
     */
    void removeListener(IssueListener listener);
    
    /**
     * Gets all issues (most recent first).
     */
    List<LogIssue> getAllIssues();
    
    /**
     * Gets issues with pagination.
     */
    List<LogIssue> getIssues(int offset, int limit);
    
    /**
     * Gets issues filtered by severity.
     */
    List<LogIssue> getIssuesBySeverity(Severity severity);
    
    /**
     * Gets issues filtered by server.
     */
    List<LogIssue> getIssuesByServer(String serverName);
    
    /**
     * Gets issues from the last N minutes.
     */
    List<LogIssue> getRecentIssues(int minutes);
    
    /**
     * Gets issues within a date range.
     */
    List<LogIssue> getIssuesByDateRange(LocalDateTime from, LocalDateTime to);
    
    /**
     * Gets issues with combined filters.
     */
    List<LogIssue> getFilteredIssues(Severity severity, String serverName,
                                      LocalDateTime from, LocalDateTime to,
                                      int offset, int limit);
    
    /**
     * Gets total count of filtered issues.
     */
    long getFilteredIssuesCount(Severity severity, String serverName,
                                 LocalDateTime from, LocalDateTime to);
    
    /**
     * Gets the earliest issue timestamp.
     */
    Optional<LocalDateTime> getEarliestIssueTime();
    
    /**
     * Gets the latest issue timestamp.
     */
    Optional<LocalDateTime> getLatestIssueTime();
    
    /**
     * Gets issues grouped by date for trending.
     */
    Map<String, Integer> getDailyTrend(int days);
    
    /**
     * Gets issues grouped by hour for trending.
     */
    Map<String, Integer> getHourlyTrend(int hours);
    
    /**
     * Gets an issue by ID.
     */
    Optional<LogIssue> getIssueById(String id);
    
    /**
     * Acknowledges an issue.
     */
    boolean acknowledgeIssue(String id);
    
    /**
     * Clears all issues.
     */
    void clearAll();
    
    /**
     * Clears acknowledged issues.
     */
    void clearAcknowledged();
    
    /**
     * Gets the total count of issues ever received.
     */
    long getTotalIssuesCount();
    
    /**
     * Gets the current number of issues in the store.
     */
    int getCurrentIssuesCount();
    
    /**
     * Gets count by severity.
     */
    long getCountBySeverity(Severity severity);
    
    /**
     * Gets counts by server.
     */
    Map<String, Long> getServerCounts();
    
    /**
     * Gets severity distribution for current issues.
     */
    Map<String, Integer> getCurrentSeverityDistribution();
    
    /**
     * Gets unique server names from current issues.
     */
    Set<String> getActiveServers();
    
    /**
     * Gets unique exception types from current issues.
     */
    Map<String, Integer> getExceptionTypeDistribution();
}
