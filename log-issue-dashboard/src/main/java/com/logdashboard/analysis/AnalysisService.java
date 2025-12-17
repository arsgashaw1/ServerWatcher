package com.logdashboard.analysis;

import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;
import com.logdashboard.store.IssueStore;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides analysis and insights on log issues.
 * Uses caching to reduce CPU usage from repeated calculations.
 */
public class AnalysisService {
    
    private final IssueStore issueStore;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    
    // Cache for dashboard stats - expires after 2 seconds to balance freshness vs CPU
    private static final long CACHE_TTL_MS = 2000;
    private final AtomicReference<CachedStats> cachedDashboardStats = new AtomicReference<>();
    private final AtomicReference<CachedReport> cachedAnalysisReport = new AtomicReference<>();
    
    private static class CachedStats {
        final DashboardStats stats;
        final long timestamp;
        final int issueCount;
        
        CachedStats(DashboardStats stats, int issueCount) {
            this.stats = stats;
            this.timestamp = System.currentTimeMillis();
            this.issueCount = issueCount;
        }
        
        boolean isValid(int currentIssueCount) {
            return System.currentTimeMillis() - timestamp < CACHE_TTL_MS 
                   && issueCount == currentIssueCount;
        }
    }
    
    private static class CachedReport {
        final AnalysisReport report;
        final long timestamp;
        final int issueCount;
        
        CachedReport(AnalysisReport report, int issueCount) {
            this.report = report;
            this.timestamp = System.currentTimeMillis();
            this.issueCount = issueCount;
        }
        
        boolean isValid(int currentIssueCount) {
            // Analysis report can be cached longer (5 seconds) since it's expensive
            return System.currentTimeMillis() - timestamp < 5000 
                   && issueCount == currentIssueCount;
        }
    }
    
    public AnalysisService(IssueStore issueStore) {
        this.issueStore = issueStore;
    }
    
    /**
     * Gets comprehensive dashboard statistics.
     * Uses caching to reduce CPU usage on repeated calls.
     */
    public DashboardStats getDashboardStats() {
        int currentCount = issueStore.getCurrentIssuesCount();
        
        // Check cache first
        CachedStats cached = cachedDashboardStats.get();
        if (cached != null && cached.isValid(currentCount)) {
            return cached.stats;
        }
        
        // Get all issues once and compute all stats in a single pass where possible
        List<LogIssue> allIssues = issueStore.getAllIssues();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff5Min = now.minusMinutes(5);
        LocalDateTime cutoff1Hour = now.minusMinutes(60);
        LocalDateTime cutoff24Hours = now.minusMinutes(1440);
        
        DashboardStats stats = new DashboardStats();
        stats.totalIssues = allIssues.size();
        
        // Count all metrics in a single pass over the issues
        int last5MinCount = 0, lastHourCount = 0, last24HoursCount = 0;
        int criticalCount = 0, exceptionCount = 0, errorCount = 0, warningCount = 0;
        int unacknowledgedCount = 0;
        Map<String, Integer> serverBreakdown = new HashMap<>();
        Map<String, Integer> exceptionTypes = new HashMap<>();
        Map<String, Integer> affectedFiles = new HashMap<>();
        Set<String> activeServers = new HashSet<>();
        
        for (LogIssue issue : allIssues) {
            LocalDateTime detectedAt = issue.getDetectedAt();
            
            // Time-based counts
            if (detectedAt.isAfter(cutoff5Min)) last5MinCount++;
            if (detectedAt.isAfter(cutoff1Hour)) lastHourCount++;
            if (detectedAt.isAfter(cutoff24Hours)) last24HoursCount++;
            
            // Severity counts
            switch (issue.getSeverity()) {
                case CRITICAL: criticalCount++; break;
                case EXCEPTION: exceptionCount++; break;
                case ERROR: errorCount++; break;
                case WARNING: warningCount++; break;
            }
            
            // Acknowledged status
            if (!issue.isAcknowledged()) unacknowledgedCount++;
            
            // Server breakdown
            String server = issue.getServerName() != null ? issue.getServerName() : "Unknown";
            serverBreakdown.merge(server, 1, Integer::sum);
            if (issue.getServerName() != null) activeServers.add(issue.getServerName());
            
            // Exception types
            exceptionTypes.merge(issue.getIssueType(), 1, Integer::sum);
            
            // Affected files
            affectedFiles.merge(issue.getFileName(), 1, Integer::sum);
        }
        
        stats.issuesLast5Min = last5MinCount;
        stats.issuesLastHour = lastHourCount;
        stats.issuesLast24Hours = last24HoursCount;
        stats.criticalCount = criticalCount;
        stats.exceptionCount = exceptionCount;
        stats.errorCount = errorCount;
        stats.warningCount = warningCount;
        stats.unacknowledgedCount = unacknowledgedCount;
        stats.activeServersCount = activeServers.size();
        stats.issueRatePerMinute = lastHourCount / 60.0;
        
        // Sort and limit results
        stats.serverBreakdown = sortAndLimit(serverBreakdown, Integer.MAX_VALUE);
        stats.topExceptionTypes = sortAndLimit(exceptionTypes, 5);
        stats.mostAffectedFiles = sortAndLimit(affectedFiles, 5);
        
        // Trends require time-bucketing (still needs separate iteration but more efficient)
        stats.recentTrend = calculateMinutelyTrendEfficient(allIssues, 10);
        stats.hourlyTrend = calculateHourlyTrendEfficient(allIssues, 24);
        
        // Cache the results
        cachedDashboardStats.set(new CachedStats(stats, currentCount));
        
        return stats;
    }
    
    private Map<String, Integer> sortAndLimit(Map<String, Integer> map, int limit) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }
    
    /**
     * Gets detailed analysis report.
     * Uses caching to reduce CPU usage since this is an expensive operation.
     */
    public AnalysisReport getAnalysisReport() {
        int currentCount = issueStore.getCurrentIssuesCount();
        
        // Check cache first
        CachedReport cached = cachedAnalysisReport.get();
        if (cached != null && cached.isValid(currentCount)) {
            return cached.report;
        }
        
        List<LogIssue> allIssues = issueStore.getAllIssues();
        
        AnalysisReport report = new AnalysisReport();
        report.generatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        report.totalIssuesAnalyzed = allIssues.size();
        
        // Single pass for severity counts and peak hours
        int[] severityCounts = new int[Severity.values().length];
        Map<Integer, Integer> hourCounts = new HashMap<>();
        Map<String, Integer> serverCounts = new HashMap<>();
        
        for (LogIssue issue : allIssues) {
            severityCounts[issue.getSeverity().ordinal()]++;
            int hour = issue.getDetectedAt().getHour();
            hourCounts.merge(hour, 1, Integer::sum);
            String server = issue.getServerName() != null ? issue.getServerName() : "Unknown";
            serverCounts.merge(server, 1, Integer::sum);
        }
        
        // Severity distribution
        report.severityDistribution = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            int count = severityCounts[s.ordinal()];
            double percentage = allIssues.isEmpty() ? 0 : (count * 100.0 / allIssues.size());
            report.severityDistribution.put(s.name(), new SeverityStats(count, percentage));
        }
        
        // Peak hours from pre-computed map
        report.peakHours = hourCounts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        // Server health from pre-computed counts
        report.serverHealthScores = calculateServerHealthFromCounts(serverCounts);
        
        // Pattern analysis - find common patterns (limit iterations)
        report.commonPatterns = findCommonPatterns(allIssues, 10);
        
        // Recurring issues detection (limit to first 1000 issues to reduce CPU)
        List<LogIssue> limitedIssues = allIssues.size() > 1000 ? allIssues.subList(0, 1000) : allIssues;
        report.recurringIssues = detectRecurringIssues(limitedIssues, 3);
        
        // Root cause candidates (limit to first 1000 issues)
        report.rootCauseCandidates = identifyRootCauseCandidates(limitedIssues);
        
        // Cache the results
        cachedAnalysisReport.set(new CachedReport(report, currentCount));
        
        return report;
    }
    
    private Map<String, Double> calculateServerHealthFromCounts(Map<String, Integer> serverCounts) {
        Map<String, Double> health = new LinkedHashMap<>();
        int maxIssues = serverCounts.values().stream().max(Integer::compareTo).orElse(1);
        
        for (Map.Entry<String, Integer> entry : serverCounts.entrySet()) {
            double healthScore = 100.0 * (1.0 - (double) entry.getValue() / maxIssues);
            health.put(entry.getKey(), Math.round(healthScore * 10) / 10.0);
        }
        return health;
    }
    
    /**
     * Detects anomalies in issue patterns.
     * Optimized to minimize redundant iterations.
     */
    public List<Anomaly> detectAnomalies() {
        List<Anomaly> anomalies = new ArrayList<>();
        List<LogIssue> allIssues = issueStore.getAllIssues();
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff5Min = now.minusMinutes(5);
        LocalDateTime cutoff30Min = now.minusMinutes(30);
        
        // Single pass to collect all needed data
        List<LogIssue> last5Min = new ArrayList<>();
        int previous30MinCount = 0;
        Set<String> recentTypes = new HashSet<>();
        Set<String> historicalTypes = new HashSet<>();
        Map<String, Integer> serverCountsLast5Min = new HashMap<>();
        
        for (LogIssue issue : allIssues) {
            LocalDateTime detectedAt = issue.getDetectedAt();
            
            if (detectedAt.isAfter(cutoff5Min)) {
                last5Min.add(issue);
                recentTypes.add(issue.getIssueType());
                String server = issue.getServerName() != null ? issue.getServerName() : "Unknown";
                serverCountsLast5Min.merge(server, 1, Integer::sum);
            }
            
            if (detectedAt.isAfter(cutoff30Min)) {
                previous30MinCount++;
            }
            
            if (detectedAt.isBefore(cutoff5Min)) {
                historicalTypes.add(issue.getIssueType());
            }
        }
        
        // Check for sudden spike (more than 5x average in last 5 minutes)
        double avgPer5Min = previous30MinCount / 6.0;
        if (last5Min.size() > avgPer5Min * 5 && avgPer5Min > 0) {
            Anomaly spike = new Anomaly();
            spike.type = "SPIKE";
            spike.severity = "HIGH";
            spike.description = String.format("Issue spike detected: %d issues in last 5 min (avg: %.1f)", 
                    last5Min.size(), avgPer5Min);
            spike.affectedServers = getAffectedServers(last5Min);
            spike.detectedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            anomalies.add(spike);
        }
        
        // Check for new exception type
        recentTypes.removeAll(historicalTypes);
        for (String newType : recentTypes) {
            Anomaly newException = new Anomaly();
            newException.type = "NEW_EXCEPTION";
            newException.severity = "MEDIUM";
            newException.description = "New exception type detected: " + newType;
            newException.detectedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            anomalies.add(newException);
        }
        
        // Check for single server having disproportionate issues
        int total = last5Min.size();
        for (Map.Entry<String, Integer> entry : serverCountsLast5Min.entrySet()) {
            if (total > 10 && entry.getValue() > total * 0.8) {
                Anomaly serverAnomaly = new Anomaly();
                serverAnomaly.type = "SERVER_CONCENTRATION";
                serverAnomaly.severity = "HIGH";
                serverAnomaly.description = String.format("Server %s has %d%% of recent issues", 
                        entry.getKey(), (int)(entry.getValue() * 100.0 / total));
                serverAnomaly.affectedServers = Collections.singletonList(entry.getKey());
                serverAnomaly.detectedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                anomalies.add(serverAnomaly);
            }
        }
        
        return anomalies;
    }
    
    private int countBySeverity(List<LogIssue> issues, Severity severity) {
        return (int) issues.stream().filter(i -> i.getSeverity() == severity).count();
    }
    
    /**
     * Efficient minutely trend calculation using pre-fetched issues list.
     */
    private Map<String, Integer> calculateMinutelyTrendEfficient(List<LogIssue> allIssues, int minutes) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> trend = new LinkedHashMap<>();
        
        for (int i = minutes - 1; i >= 0; i--) {
            LocalDateTime minute = now.minusMinutes(i).truncatedTo(ChronoUnit.MINUTES);
            trend.put(minute.format(TIME_FORMATTER), 0);
        }
        
        LocalDateTime cutoff = now.minusMinutes(minutes);
        for (LogIssue issue : allIssues) {
            if (issue.getDetectedAt().isAfter(cutoff)) {
                LocalDateTime minute = issue.getDetectedAt().truncatedTo(ChronoUnit.MINUTES);
                String key = minute.format(TIME_FORMATTER);
                if (trend.containsKey(key)) {
                    trend.merge(key, 1, Integer::sum);
                }
            }
        }
        
        return trend;
    }
    
    /**
     * Efficient hourly trend calculation using pre-fetched issues list.
     */
    private Map<String, Integer> calculateHourlyTrendEfficient(List<LogIssue> allIssues, int hours) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> trend = new LinkedHashMap<>();
        
        for (int i = hours - 1; i >= 0; i--) {
            LocalDateTime hour = now.minusHours(i).truncatedTo(ChronoUnit.HOURS);
            trend.put(hour.format(DATE_FORMATTER), 0);
        }
        
        LocalDateTime cutoff = now.minusHours(hours);
        for (LogIssue issue : allIssues) {
            if (issue.getDetectedAt().isAfter(cutoff)) {
                LocalDateTime hour = issue.getDetectedAt().truncatedTo(ChronoUnit.HOURS);
                String key = hour.format(DATE_FORMATTER);
                if (trend.containsKey(key)) {
                    trend.merge(key, 1, Integer::sum);
                }
            }
        }
        
        return trend;
    }
    
    private Map<String, Integer> calculateServerBreakdown(List<LogIssue> issues) {
        Map<String, Integer> breakdown = new HashMap<>();
        for (LogIssue issue : issues) {
            String server = issue.getServerName() != null ? issue.getServerName() : "Unknown";
            breakdown.merge(server, 1, Integer::sum);
        }
        return breakdown.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }
    
    private Map<String, Integer> getTopExceptionTypes(List<LogIssue> issues, int limit) {
        Map<String, Integer> types = new HashMap<>();
        for (LogIssue issue : issues) {
            types.merge(issue.getIssueType(), 1, Integer::sum);
        }
        return types.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }
    
    private Map<String, Integer> getMostAffectedFiles(List<LogIssue> issues, int limit) {
        Map<String, Integer> files = new HashMap<>();
        for (LogIssue issue : issues) {
            files.merge(issue.getFileName(), 1, Integer::sum);
        }
        return files.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }
    
    private List<String> findCommonPatterns(List<LogIssue> issues, int limit) {
        Map<String, Integer> patterns = new HashMap<>();
        Pattern classPattern = Pattern.compile("([a-zA-Z]+(?:\\.[a-zA-Z]+)+)");
        
        for (LogIssue issue : issues) {
            Matcher matcher = classPattern.matcher(issue.getMessage());
            while (matcher.find()) {
                String match = matcher.group(1);
                if (match.length() > 10) {
                    patterns.merge(match, 1, Integer::sum);
                }
            }
        }
        
        return patterns.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    // findPeakHours and calculateServerHealth are now computed inline in getAnalysisReport()
    // for better performance (single-pass computation)
    
    private List<RecurringIssue> detectRecurringIssues(List<LogIssue> issues, int minOccurrences) {
        Map<String, List<LogIssue>> grouped = new HashMap<>();
        
        // Group by normalized message (remove numbers and timestamps)
        for (LogIssue issue : issues) {
            String normalized = normalizeMessage(issue.getMessage());
            grouped.computeIfAbsent(normalized, k -> new ArrayList<>()).add(issue);
        }
        
        List<RecurringIssue> recurring = new ArrayList<>();
        for (Map.Entry<String, List<LogIssue>> entry : grouped.entrySet()) {
            if (entry.getValue().size() >= minOccurrences) {
                RecurringIssue ri = new RecurringIssue();
                ri.pattern = entry.getKey();
                ri.occurrences = entry.getValue().size();
                ri.firstSeen = entry.getValue().stream()
                        .map(LogIssue::getDetectedAt)
                        .min(LocalDateTime::compareTo)
                        .map(dt -> dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                        .orElse("Unknown");
                ri.lastSeen = entry.getValue().stream()
                        .map(LogIssue::getDetectedAt)
                        .max(LocalDateTime::compareTo)
                        .map(dt -> dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                        .orElse("Unknown");
                ri.servers = entry.getValue().stream()
                        .map(LogIssue::getServerName)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
                recurring.add(ri);
            }
        }
        
        return recurring.stream()
                .sorted((a, b) -> Integer.compare(b.occurrences, a.occurrences))
                .limit(10)
                .collect(Collectors.toList());
    }
    
    private String normalizeMessage(String message) {
        // Remove numbers, timestamps, and UUIDs
        return message
                .replaceAll("\\d{4}-\\d{2}-\\d{2}", "DATE")
                .replaceAll("\\d{2}:\\d{2}:\\d{2}", "TIME")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "UUID")
                .replaceAll("\\d+", "N")
                .trim();
    }
    
    private List<RootCauseCandidate> identifyRootCauseCandidates(List<LogIssue> issues) {
        List<RootCauseCandidate> candidates = new ArrayList<>();
        
        // Look for NullPointerExceptions with class names
        Map<String, Integer> nullPointerLocations = new HashMap<>();
        for (LogIssue issue : issues) {
            if (issue.getIssueType().contains("NullPointer")) {
                String trace = issue.getFullStackTrace();
                Pattern atPattern = Pattern.compile("at ([^(]+)\\(");
                Matcher matcher = atPattern.matcher(trace);
                if (matcher.find()) {
                    nullPointerLocations.merge(matcher.group(1), 1, Integer::sum);
                }
            }
        }
        
        for (Map.Entry<String, Integer> entry : nullPointerLocations.entrySet()) {
            if (entry.getValue() >= 2) {
                RootCauseCandidate rc = new RootCauseCandidate();
                rc.type = "NULL_POINTER_HOTSPOT";
                rc.location = entry.getKey();
                rc.occurrences = entry.getValue();
                rc.suggestion = "Consider adding null checks or using Optional in " + entry.getKey();
                candidates.add(rc);
            }
        }
        
        return candidates.stream()
                .sorted((a, b) -> Integer.compare(b.occurrences, a.occurrences))
                .limit(5)
                .collect(Collectors.toList());
    }
    
    private List<String> getAffectedServers(List<LogIssue> issues) {
        return issues.stream()
                .map(LogIssue::getServerName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }
    
    // Data classes for analysis results
    
    public static class DashboardStats {
        public int totalIssues;
        public int issuesLast5Min;
        public int issuesLastHour;
        public int issuesLast24Hours;
        public int criticalCount;
        public int exceptionCount;
        public int errorCount;
        public int warningCount;
        public Map<String, Integer> recentTrend;
        public Map<String, Integer> hourlyTrend;
        public Map<String, Integer> serverBreakdown;
        public Map<String, Integer> topExceptionTypes;
        public Map<String, Integer> mostAffectedFiles;
        public int activeServersCount;
        public double issueRatePerMinute;
        public int unacknowledgedCount;
    }
    
    public static class AnalysisReport {
        public String generatedAt;
        public int totalIssuesAnalyzed;
        public Map<String, SeverityStats> severityDistribution;
        public List<String> commonPatterns;
        public List<Integer> peakHours;
        public Map<String, Double> serverHealthScores;
        public List<RecurringIssue> recurringIssues;
        public List<RootCauseCandidate> rootCauseCandidates;
    }
    
    public static class SeverityStats {
        public int count;
        public double percentage;
        
        public SeverityStats(int count, double percentage) {
            this.count = count;
            this.percentage = Math.round(percentage * 10) / 10.0;
        }
    }
    
    public static class Anomaly {
        public String type;
        public String severity;
        public String description;
        public List<String> affectedServers;
        public String detectedAt;
    }
    
    public static class RecurringIssue {
        public String pattern;
        public int occurrences;
        public String firstSeen;
        public String lastSeen;
        public List<String> servers;
    }
    
    public static class RootCauseCandidate {
        public String type;
        public String location;
        public int occurrences;
        public String suggestion;
    }
}
