package com.logdashboard.analysis;

import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;
import com.logdashboard.store.IssueStore;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides analysis and insights on log issues.
 */
public class AnalysisService {
    
    private final IssueStore issueStore;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    
    public AnalysisService(IssueStore issueStore) {
        this.issueStore = issueStore;
    }
    
    /**
     * Gets comprehensive dashboard statistics.
     */
    public DashboardStats getDashboardStats() {
        List<LogIssue> allIssues = issueStore.getAllIssues();
        List<LogIssue> last5Min = issueStore.getRecentIssues(5);
        List<LogIssue> lastHour = issueStore.getRecentIssues(60);
        List<LogIssue> last24Hours = issueStore.getRecentIssues(1440);
        
        DashboardStats stats = new DashboardStats();
        stats.totalIssues = allIssues.size();
        stats.issuesLast5Min = last5Min.size();
        stats.issuesLastHour = lastHour.size();
        stats.issuesLast24Hours = last24Hours.size();
        
        // Count by severity
        stats.criticalCount = countBySeverity(allIssues, Severity.CRITICAL);
        stats.exceptionCount = countBySeverity(allIssues, Severity.EXCEPTION);
        stats.errorCount = countBySeverity(allIssues, Severity.ERROR);
        stats.warningCount = countBySeverity(allIssues, Severity.WARNING);
        
        // Recent trend (issues per minute in last 10 minutes)
        stats.recentTrend = calculateMinutelyTrend(10);
        
        // Hourly trend for last 24 hours
        stats.hourlyTrend = calculateHourlyTrend(24);
        
        // Server breakdown
        stats.serverBreakdown = calculateServerBreakdown(allIssues);
        
        // Top exception types
        stats.topExceptionTypes = getTopExceptionTypes(allIssues, 5);
        
        // Most affected files
        stats.mostAffectedFiles = getMostAffectedFiles(allIssues, 5);
        
        // Active servers count
        stats.activeServersCount = issueStore.getActiveServers().size();
        
        // Issue rate (issues per minute average in last hour)
        stats.issueRatePerMinute = lastHour.size() / 60.0;
        
        // Unacknowledged count
        stats.unacknowledgedCount = (int) allIssues.stream()
                .filter(i -> !i.isAcknowledged())
                .count();
        
        return stats;
    }
    
    /**
     * Gets detailed analysis report.
     */
    public AnalysisReport getAnalysisReport() {
        List<LogIssue> allIssues = issueStore.getAllIssues();
        
        AnalysisReport report = new AnalysisReport();
        report.generatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        report.totalIssuesAnalyzed = allIssues.size();
        
        // Severity distribution
        report.severityDistribution = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            int count = countBySeverity(allIssues, s);
            double percentage = allIssues.isEmpty() ? 0 : (count * 100.0 / allIssues.size());
            report.severityDistribution.put(s.name(), new SeverityStats(count, percentage));
        }
        
        // Pattern analysis - find common patterns
        report.commonPatterns = findCommonPatterns(allIssues, 10);
        
        // Time-based analysis
        report.peakHours = findPeakHours(allIssues);
        
        // Server health scores
        report.serverHealthScores = calculateServerHealth(allIssues);
        
        // Recurring issues detection
        report.recurringIssues = detectRecurringIssues(allIssues, 3);
        
        // Root cause candidates
        report.rootCauseCandidates = identifyRootCauseCandidates(allIssues);
        
        return report;
    }
    
    /**
     * Detects anomalies in issue patterns.
     */
    public List<Anomaly> detectAnomalies() {
        List<Anomaly> anomalies = new ArrayList<>();
        List<LogIssue> allIssues = issueStore.getAllIssues();
        
        // Check for sudden spike (more than 5x average in last 5 minutes)
        List<LogIssue> last5Min = issueStore.getRecentIssues(5);
        List<LogIssue> previous30Min = issueStore.getRecentIssues(30);
        double avgPer5Min = previous30Min.size() / 6.0;
        if (last5Min.size() > avgPer5Min * 5 && avgPer5Min > 0) {
            Anomaly spike = new Anomaly();
            spike.type = "SPIKE";
            spike.severity = "HIGH";
            spike.description = String.format("Issue spike detected: %d issues in last 5 min (avg: %.1f)", 
                    last5Min.size(), avgPer5Min);
            spike.affectedServers = getAffectedServers(last5Min);
            spike.detectedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            anomalies.add(spike);
        }
        
        // Check for new exception type
        Set<String> recentTypes = last5Min.stream()
                .map(LogIssue::getIssueType)
                .collect(Collectors.toSet());
        Set<String> historicalTypes = allIssues.stream()
                .filter(i -> i.getDetectedAt().isBefore(LocalDateTime.now().minusMinutes(5)))
                .map(LogIssue::getIssueType)
                .collect(Collectors.toSet());
        recentTypes.removeAll(historicalTypes);
        if (!recentTypes.isEmpty()) {
            for (String newType : recentTypes) {
                Anomaly newException = new Anomaly();
                newException.type = "NEW_EXCEPTION";
                newException.severity = "MEDIUM";
                newException.description = "New exception type detected: " + newType;
                newException.detectedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                anomalies.add(newException);
            }
        }
        
        // Check for single server having disproportionate issues
        Map<String, Integer> serverCounts = calculateServerBreakdown(last5Min);
        int total = last5Min.size();
        for (Map.Entry<String, Integer> entry : serverCounts.entrySet()) {
            if (total > 10 && entry.getValue() > total * 0.8) {
                Anomaly serverAnomaly = new Anomaly();
                serverAnomaly.type = "SERVER_CONCENTRATION";
                serverAnomaly.severity = "HIGH";
                serverAnomaly.description = String.format("Server %s has %d%% of recent issues", 
                        entry.getKey(), (int)(entry.getValue() * 100.0 / total));
                serverAnomaly.affectedServers = Collections.singletonList(entry.getKey());
                serverAnomaly.detectedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                anomalies.add(serverAnomaly);
            }
        }
        
        return anomalies;
    }
    
    private int countBySeverity(List<LogIssue> issues, Severity severity) {
        return (int) issues.stream().filter(i -> i.getSeverity() == severity).count();
    }
    
    private Map<String, Integer> calculateMinutelyTrend(int minutes) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> trend = new LinkedHashMap<>();
        
        for (int i = minutes - 1; i >= 0; i--) {
            LocalDateTime minute = now.minusMinutes(i).truncatedTo(ChronoUnit.MINUTES);
            trend.put(minute.format(TIME_FORMATTER), 0);
        }
        
        LocalDateTime cutoff = now.minusMinutes(minutes);
        for (LogIssue issue : issueStore.getAllIssues()) {
            if (issue.getDetectedAt().isAfter(cutoff)) {
                LocalDateTime minute = issue.getDetectedAt().truncatedTo(ChronoUnit.MINUTES);
                String key = minute.format(TIME_FORMATTER);
                trend.merge(key, 1, Integer::sum);
            }
        }
        
        return trend;
    }
    
    private Map<String, Integer> calculateHourlyTrend(int hours) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> trend = new LinkedHashMap<>();
        
        for (int i = hours - 1; i >= 0; i--) {
            LocalDateTime hour = now.minusHours(i).truncatedTo(ChronoUnit.HOURS);
            trend.put(hour.format(DATE_FORMATTER), 0);
        }
        
        LocalDateTime cutoff = now.minusHours(hours);
        for (LogIssue issue : issueStore.getAllIssues()) {
            if (issue.getDetectedAt().isAfter(cutoff)) {
                LocalDateTime hour = issue.getDetectedAt().truncatedTo(ChronoUnit.HOURS);
                String key = hour.format(DATE_FORMATTER);
                trend.merge(key, 1, Integer::sum);
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
    
    private List<Integer> findPeakHours(List<LogIssue> issues) {
        Map<Integer, Integer> hourCounts = new HashMap<>();
        for (LogIssue issue : issues) {
            int hour = issue.getDetectedAt().getHour();
            hourCounts.merge(hour, 1, Integer::sum);
        }
        
        return hourCounts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    private Map<String, Double> calculateServerHealth(List<LogIssue> issues) {
        Map<String, Integer> serverCounts = calculateServerBreakdown(issues);
        Map<String, Double> health = new LinkedHashMap<>();
        
        int maxIssues = serverCounts.values().stream().max(Integer::compareTo).orElse(1);
        
        for (Map.Entry<String, Integer> entry : serverCounts.entrySet()) {
            // Health score: 100 = no issues, 0 = max issues
            double healthScore = 100.0 * (1.0 - (double) entry.getValue() / maxIssues);
            health.put(entry.getKey(), Math.round(healthScore * 10) / 10.0);
        }
        
        return health;
    }
    
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
