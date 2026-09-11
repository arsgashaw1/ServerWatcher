package com.logdashboard.store;

import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

        /**
         * Called when repeats of an existing issue were recorded.
         */
        default void onOccurrences(String issueId, int occurrenceCount, LocalDateTime lastSeenAt) {
        }
    }

    /**
     * Adds a new issue to the store.
     */
    void addIssue(LogIssue issue);

    /**
     * Adds repeated occurrences (duplicates suppressed by the parser) to an existing issue.
     *
     * @return false if the issue no longer exists (e.g. cleared or deleted by retention)
     */
    boolean recordOccurrences(String issueId, int additionalOccurrences, LocalDateTime lastSeenAt);

    /**
     * Deletes issues whose most recent occurrence is before the cutoff (retention).
     *
     * @return number of issues deleted
     */
    int deleteIssuesNotSeenSince(LocalDateTime cutoff);
    
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
     * Gets issues with combined filters. searchText is matched case-insensitively against the
     * message, issue type, file, server and stack trace; null or blank disables text search.
     */
    List<LogIssue> getFilteredIssues(Severity severity, String serverName, String searchText,
                                      LocalDateTime from, LocalDateTime to,
                                      int offset, int limit);

    /**
     * Gets issues with combined filters, without text search.
     */
    default List<LogIssue> getFilteredIssues(Severity severity, String serverName,
                                             LocalDateTime from, LocalDateTime to,
                                             int offset, int limit) {
        return getFilteredIssues(severity, serverName, null, from, to, offset, limit);
    }
    
    /**
     * Gets total count of filtered issues.
     */
    long getFilteredIssuesCount(Severity severity, String serverName, String searchText,
                                 LocalDateTime from, LocalDateTime to);

    /**
     * Gets total count of filtered issues, without text search.
     */
    default long getFilteredIssuesCount(Severity severity, String serverName,
                                        LocalDateTime from, LocalDateTime to) {
        return getFilteredIssuesCount(severity, serverName, null, from, to);
    }
    
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

    /**
     * Issue count for one combination of severity, acknowledged flag, server, type and file.
     */
    final class IssueGroupCount {
        public final Severity severity;
        public final boolean acknowledged;
        public final String serverName;
        public final String issueType;
        public final String fileName;
        public final long count;

        public IssueGroupCount(Severity severity, boolean acknowledged, String serverName,
                               String issueType, String fileName, long count) {
            this.severity = severity;
            this.acknowledged = acknowledged;
            this.serverName = serverName;
            this.issueType = issueType;
            this.fileName = fileName;
            this.count = count;
        }
    }

    /**
     * Gets issue counts grouped by severity, acknowledged flag, server, type and file.
     * Lets statistics be computed without loading every issue (with stack traces) into memory.
     */
    default List<IssueGroupCount> getIssueGroupCounts() {
        Map<List<Object>, long[]> groups = new LinkedHashMap<>();
        for (LogIssue issue : getAllIssues()) {
            List<Object> key = Arrays.asList(issue.getSeverity(), issue.isAcknowledged(),
                issue.getServerName(), issue.getIssueType(), issue.getFileName());
            groups.computeIfAbsent(key, k -> new long[1])[0]++;
        }
        List<IssueGroupCount> result = new ArrayList<>();
        for (Map.Entry<List<Object>, long[]> entry : groups.entrySet()) {
            List<Object> key = entry.getKey();
            result.add(new IssueGroupCount((Severity) key.get(0), (Boolean) key.get(1),
                (String) key.get(2), (String) key.get(3), (String) key.get(4), entry.getValue()[0]));
        }
        return result;
    }

    /**
     * Gets detection timestamps of issues detected after the given time.
     */
    default List<LocalDateTime> getDetectionTimesSince(LocalDateTime since) {
        List<LocalDateTime> times = new ArrayList<>();
        for (LogIssue issue : getAllIssues()) {
            if (issue.getDetectedAt().isAfter(since)) {
                times.add(issue.getDetectedAt());
            }
        }
        return times;
    }
}
