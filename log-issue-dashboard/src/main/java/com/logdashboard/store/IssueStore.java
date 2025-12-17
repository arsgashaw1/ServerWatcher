package com.logdashboard.store;

import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe store for log issues that supports web access.
 * Provides statistics and filtering capabilities for the dashboard.
 * 
 * Memory optimization: enforces maximum issue limit and provides
 * efficient access patterns to minimize GC pressure.
 */
public class IssueStore {
    
    private final Deque<LogIssue> issues;
    private final int maxIssues;
    private final List<IssueListener> listeners;
    private final AtomicLong totalIssuesCount;
    private final Map<Severity, AtomicLong> severityCounts;
    private final Map<String, AtomicLong> serverCounts;
    
    // Maximum limits to prevent memory exhaustion
    private static final int MAX_LISTENERS = 100;
    private static final int MAX_FILTER_RESULTS = 10000;
    
    public interface IssueListener {
        void onNewIssue(LogIssue issue);
    }
    
    public IssueStore(int maxIssues) {
        // Enforce a reasonable maximum to prevent memory exhaustion
        this.maxIssues = Math.min(maxIssues, 50000);
        this.issues = new ConcurrentLinkedDeque<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.totalIssuesCount = new AtomicLong(0);
        this.severityCounts = new ConcurrentHashMap<>();
        this.serverCounts = new ConcurrentHashMap<>();
        
        for (Severity s : Severity.values()) {
            severityCounts.put(s, new AtomicLong(0));
        }
    }
    
    /**
     * Adds a new issue to the store.
     */
    public void addIssue(LogIssue issue) {
        issues.addFirst(issue);
        totalIssuesCount.incrementAndGet();
        
        // Update severity count
        severityCounts.computeIfAbsent(issue.getSeverity(), k -> new AtomicLong(0))
                .incrementAndGet();
        
        // Update server count
        String serverName = issue.getServerName() != null ? issue.getServerName() : "Unknown";
        serverCounts.computeIfAbsent(serverName, k -> new AtomicLong(0))
                .incrementAndGet();
        
        // Trim if exceeded max
        while (issues.size() > maxIssues) {
            issues.removeLast();
        }
        
        // Notify listeners
        for (IssueListener listener : listeners) {
            try {
                listener.onNewIssue(issue);
            } catch (Exception e) {
                System.err.println("Error notifying listener: " + e.getMessage());
            }
        }
    }
    
    /**
     * Adds a listener for new issues (for WebSocket or SSE).
     * Returns false if max listeners limit reached.
     */
    public boolean addListener(IssueListener listener) {
        if (listeners.size() >= MAX_LISTENERS) {
            System.err.println("Warning: Max listeners limit (" + MAX_LISTENERS + ") reached, rejecting new listener");
            return false;
        }
        listeners.add(listener);
        return true;
    }
    
    /**
     * Removes a listener.
     */
    public void removeListener(IssueListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Gets all issues (most recent first).
     */
    public List<LogIssue> getAllIssues() {
        return new ArrayList<>(issues);
    }
    
    /**
     * Gets issues with pagination.
     */
    public List<LogIssue> getIssues(int offset, int limit) {
        return issues.stream()
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * Gets issues filtered by severity.
     */
    public List<LogIssue> getIssuesBySeverity(Severity severity) {
        return issues.stream()
                .filter(i -> i.getSeverity() == severity)
                .collect(Collectors.toList());
    }
    
    /**
     * Gets issues filtered by server.
     */
    public List<LogIssue> getIssuesByServer(String serverName) {
        return issues.stream()
                .filter(i -> serverName.equals(i.getServerName()))
                .collect(Collectors.toList());
    }
    
    /**
     * Gets issues from the last N minutes.
     */
    public List<LogIssue> getRecentIssues(int minutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        return issues.stream()
                .filter(i -> i.getDetectedAt().isAfter(cutoff))
                .collect(Collectors.toList());
    }
    
    /**
     * Gets issues within a date range.
     */
    public List<LogIssue> getIssuesByDateRange(LocalDateTime from, LocalDateTime to) {
        return issues.stream()
                .filter(i -> {
                    LocalDateTime dt = i.getDetectedAt();
                    return (from == null || !dt.isBefore(from)) && (to == null || !dt.isAfter(to));
                })
                .collect(Collectors.toList());
    }
    
    /**
     * Gets issues with combined filters (severity, server, date range).
     * Enforces maximum result limit to prevent memory exhaustion.
     */
    public List<LogIssue> getFilteredIssues(Severity severity, String serverName, 
                                             LocalDateTime from, LocalDateTime to,
                                             int offset, int limit) {
        // Enforce maximum limit to prevent memory exhaustion
        int effectiveLimit = Math.min(limit, MAX_FILTER_RESULTS);
        int effectiveOffset = Math.max(0, offset);
        
        return issues.stream()
                .filter(i -> severity == null || i.getSeverity() == severity)
                .filter(i -> serverName == null || serverName.isEmpty() || serverName.equals(i.getServerName()))
                .filter(i -> {
                    LocalDateTime dt = i.getDetectedAt();
                    return (from == null || !dt.isBefore(from)) && (to == null || !dt.isAfter(to));
                })
                .skip(effectiveOffset)
                .limit(effectiveLimit)
                .collect(Collectors.toList());
    }
    
    /**
     * Gets total count of filtered issues.
     */
    public long getFilteredIssuesCount(Severity severity, String serverName, 
                                        LocalDateTime from, LocalDateTime to) {
        return issues.stream()
                .filter(i -> severity == null || i.getSeverity() == severity)
                .filter(i -> serverName == null || serverName.isEmpty() || serverName.equals(i.getServerName()))
                .filter(i -> {
                    LocalDateTime dt = i.getDetectedAt();
                    return (from == null || !dt.isBefore(from)) && (to == null || !dt.isAfter(to));
                })
                .count();
    }
    
    /**
     * Gets the earliest issue timestamp.
     */
    public Optional<LocalDateTime> getEarliestIssueTime() {
        return issues.stream()
                .map(LogIssue::getDetectedAt)
                .min(LocalDateTime::compareTo);
    }
    
    /**
     * Gets the latest issue timestamp.
     */
    public Optional<LocalDateTime> getLatestIssueTime() {
        return issues.stream()
                .map(LogIssue::getDetectedAt)
                .max(LocalDateTime::compareTo);
    }
    
    /**
     * Gets issues grouped by date for trending.
     */
    public Map<String, Integer> getDailyTrend(int days) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> trend = new LinkedHashMap<>();
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd");
        
        // Initialize all days (from days-1 ago to today)
        for (int i = days - 1; i >= 0; i--) {
            LocalDateTime day = now.minusDays(i).truncatedTo(ChronoUnit.DAYS);
            trend.put(day.format(formatter), 0);
        }
        
        // Count issues per day - use start of the first day as cutoff to match initialized keys
        LocalDateTime cutoff = now.minusDays(days - 1).truncatedTo(ChronoUnit.DAYS);
        for (LogIssue issue : issues) {
            LocalDateTime issueTime = issue.getDetectedAt();
            if (!issueTime.isBefore(cutoff)) {
                String key = issueTime.format(formatter);
                // Only count if key exists in our pre-initialized map
                if (trend.containsKey(key)) {
                    trend.merge(key, 1, Integer::sum);
                }
            }
        }
        
        return trend;
    }
    
    /**
     * Gets an issue by ID.
     */
    public Optional<LogIssue> getIssueById(String id) {
        return issues.stream()
                .filter(i -> i.getId().equals(id))
                .findFirst();
    }
    
    /**
     * Acknowledges an issue.
     */
    public boolean acknowledgeIssue(String id) {
        Optional<LogIssue> issue = getIssueById(id);
        if (issue.isPresent()) {
            issue.get().setAcknowledged(true);
            return true;
        }
        return false;
    }
    
    /**
     * Clears all issues.
     */
    public void clearAll() {
        issues.clear();
    }
    
    /**
     * Clears acknowledged issues.
     */
    public void clearAcknowledged() {
        issues.removeIf(LogIssue::isAcknowledged);
    }
    
    /**
     * Gets the total count of issues ever received.
     */
    public long getTotalIssuesCount() {
        return totalIssuesCount.get();
    }
    
    /**
     * Gets the current number of issues in the store.
     */
    public int getCurrentIssuesCount() {
        return issues.size();
    }
    
    /**
     * Gets count by severity.
     */
    public long getCountBySeverity(Severity severity) {
        AtomicLong count = severityCounts.get(severity);
        return count != null ? count.get() : 0;
    }
    
    /**
     * Gets counts by server.
     */
    public Map<String, Long> getServerCounts() {
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : serverCounts.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }
    
    /**
     * Gets severity distribution for current issues.
     */
    public Map<String, Integer> getCurrentSeverityDistribution() {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            distribution.put(s.name(), 0);
        }
        for (LogIssue issue : issues) {
            String severity = issue.getSeverity().name();
            distribution.merge(severity, 1, Integer::sum);
        }
        return distribution;
    }
    
    /**
     * Gets issues grouped by hour for trending.
     */
    public Map<String, Integer> getHourlyTrend(int hours) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> trend = new LinkedHashMap<>();
        
        // Initialize all hours
        for (int i = hours - 1; i >= 0; i--) {
            LocalDateTime hour = now.minusHours(i).truncatedTo(ChronoUnit.HOURS);
            trend.put(hour.getHour() + ":00", 0);
        }
        
        // Count issues per hour
        LocalDateTime cutoff = now.minusHours(hours);
        for (LogIssue issue : issues) {
            if (issue.getDetectedAt().isAfter(cutoff)) {
                LocalDateTime hour = issue.getDetectedAt().truncatedTo(ChronoUnit.HOURS);
                String key = hour.getHour() + ":00";
                trend.merge(key, 1, Integer::sum);
            }
        }
        
        return trend;
    }
    
    /**
     * Gets unique server names from current issues.
     */
    public Set<String> getActiveServers() {
        return issues.stream()
                .map(LogIssue::getServerName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }
    
    /**
     * Gets unique exception types from current issues.
     */
    public Map<String, Integer> getExceptionTypeDistribution() {
        Map<String, Integer> distribution = new HashMap<>();
        for (LogIssue issue : issues) {
            String type = issue.getIssueType();
            distribution.merge(type, 1, Integer::sum);
        }
        return distribution.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }
}
