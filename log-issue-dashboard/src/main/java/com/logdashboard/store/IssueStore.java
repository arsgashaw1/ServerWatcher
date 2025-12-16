package com.logdashboard.store;

import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe store for log issues that supports web access.
 * Provides statistics and filtering capabilities for the dashboard.
 */
public class IssueStore {
    
    private final Deque<LogIssue> issues;
    private final int maxIssues;
    private final List<IssueListener> listeners;
    private final AtomicLong totalIssuesCount;
    private final Map<Severity, AtomicLong> severityCounts;
    private final Map<String, AtomicLong> serverCounts;
    
    public interface IssueListener {
        void onNewIssue(LogIssue issue);
    }
    
    public IssueStore(int maxIssues) {
        this.issues = new ConcurrentLinkedDeque<>();
        this.maxIssues = maxIssues;
        this.listeners = new CopyOnWriteArrayList<>();
        this.totalIssuesCount = new AtomicLong(0);
        this.severityCounts = new HashMap<>();
        this.serverCounts = new HashMap<>();
        
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
     */
    public void addListener(IssueListener listener) {
        listeners.add(listener);
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
