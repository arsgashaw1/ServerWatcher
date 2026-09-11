package com.logdashboard.analysis;

import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;
import com.logdashboard.store.IssueRepository;
import com.logdashboard.store.IssueRepository.IssueGroupCount;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides analysis and insights on log issues.
 *
 * Performance notes:
 * - Statistics come from aggregate queries (grouped counts and detection timestamps)
 *   instead of loading every issue with its stack trace into memory.
 * - Results are cached and concurrent requests (several open dashboards) share a single
 *   computation instead of each triggering their own.
 */
public class AnalysisService {

    private final IssueRepository issueStore;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    // Cached results are reused for at least MIN_CACHE_MS, and up to the max age while
    // the issue count is unchanged. Live issues reach the UI via SSE, so this only
    // delays the summary numbers by a few seconds.
    private static final long MIN_CACHE_MS = 3000;
    private static final long MAX_CACHE_MS = 15000;
    private static final long ANALYSIS_MAX_CACHE_MS = 30000;

    // Pattern and peak-hour analysis runs over the most recent issues
    private static final int ANALYSIS_SAMPLE_SIZE = 10000;
    private static final int RECURRING_SAMPLE_SIZE = 1000;

    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("([a-zA-Z]+(?:\\.[a-zA-Z]+)+)");
    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile("at ([^(]+)\\(");
    private static final Pattern NORMALIZE_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern NORMALIZE_TIME = Pattern.compile("\\d{2}:\\d{2}:\\d{2}");
    private static final Pattern NORMALIZE_UUID =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern NORMALIZE_NUMBER = Pattern.compile("\\d+");

    private final AtomicLong cacheGeneration = new AtomicLong();
    private final Object dashboardLock = new Object();
    private final Object analysisLock = new Object();
    private final Object anomalyLock = new Object();
    private volatile Cached<DashboardStats> cachedDashboardStats;
    private volatile Cached<AnalysisReport> cachedAnalysisReport;
    private volatile Cached<List<Anomaly>> cachedAnomalies;

    private static final class Cached<T> {
        final T value;
        final int issueCount;
        final long generation;
        final long timestamp;

        Cached(T value, int issueCount, long generation) {
            this.value = value;
            this.issueCount = issueCount;
            this.generation = generation;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public AnalysisService(IssueRepository issueStore) {
        this.issueStore = issueStore;
    }

    /**
     * Discards cached results, e.g. after issues are cleared or acknowledged.
     */
    public void invalidateCaches() {
        cacheGeneration.incrementAndGet();
    }

    private boolean isFresh(Cached<?> cached, int currentIssueCount, long maxAgeMs) {
        if (cached == null || cached.generation != cacheGeneration.get()) {
            return false;
        }
        long age = System.currentTimeMillis() - cached.timestamp;
        return age < MIN_CACHE_MS || (age < maxAgeMs && cached.issueCount == currentIssueCount);
    }

    /**
     * Gets comprehensive dashboard statistics.
     */
    public DashboardStats getDashboardStats() {
        int currentCount = issueStore.getCurrentIssuesCount();
        Cached<DashboardStats> cached = cachedDashboardStats;
        if (isFresh(cached, currentCount, MAX_CACHE_MS)) {
            return cached.value;
        }
        synchronized (dashboardLock) {
            cached = cachedDashboardStats;
            if (isFresh(cached, currentCount, MAX_CACHE_MS)) {
                return cached.value;
            }
            long generation = cacheGeneration.get();
            DashboardStats stats = computeDashboardStats();
            cachedDashboardStats = new Cached<>(stats, currentCount, generation);
            return stats;
        }
    }

    private DashboardStats computeDashboardStats() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff5Min = now.minusMinutes(5);
        LocalDateTime cutoff1Hour = now.minusMinutes(60);
        LocalDateTime cutoff24Hours = now.minusMinutes(1440);

        DashboardStats stats = new DashboardStats();

        long total = 0;
        int criticalCount = 0, exceptionCount = 0, errorCount = 0, warningCount = 0;
        int unacknowledgedCount = 0;
        Map<String, Integer> serverBreakdown = new HashMap<>();
        Map<String, Integer> exceptionTypes = new HashMap<>();
        Map<String, Integer> affectedFiles = new HashMap<>();
        Set<String> activeServers = new HashSet<>();

        for (IssueGroupCount group : issueStore.getIssueGroupCounts()) {
            int count = (int) group.count;
            total += count;

            switch (group.severity) {
                case CRITICAL: criticalCount += count; break;
                case EXCEPTION: exceptionCount += count; break;
                case ERROR: errorCount += count; break;
                case WARNING: warningCount += count; break;
            }

            if (!group.acknowledged) unacknowledgedCount += count;

            String server = group.serverName != null ? group.serverName : "Unknown";
            serverBreakdown.merge(server, count, Integer::sum);
            if (group.serverName != null) activeServers.add(group.serverName);

            exceptionTypes.merge(group.issueType, count, Integer::sum);
            affectedFiles.merge(group.fileName, count, Integer::sum);
        }

        // Time-based metrics only need detection timestamps from the last 24 hours
        List<LocalDateTime> recentTimes = issueStore.getDetectionTimesSince(now.minusHours(24));
        int last5MinCount = 0, lastHourCount = 0, last24HoursCount = 0;
        for (LocalDateTime detectedAt : recentTimes) {
            if (detectedAt.isAfter(cutoff5Min)) last5MinCount++;
            if (detectedAt.isAfter(cutoff1Hour)) lastHourCount++;
            if (detectedAt.isAfter(cutoff24Hours)) last24HoursCount++;
        }

        stats.totalIssues = (int) total;
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

        stats.serverBreakdown = sortAndLimit(serverBreakdown, Integer.MAX_VALUE);
        stats.topExceptionTypes = sortAndLimit(exceptionTypes, 5);
        stats.mostAffectedFiles = sortAndLimit(affectedFiles, 5);

        stats.recentTrend = calculateMinutelyTrend(recentTimes, now, 10);
        stats.hourlyTrend = calculateHourlyTrend(recentTimes, now, 24);

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
     * Severity and server figures cover all issues; peak hours and message patterns are
     * computed over the most recent issues to keep the cost bounded.
     */
    public AnalysisReport getAnalysisReport() {
        int currentCount = issueStore.getCurrentIssuesCount();
        Cached<AnalysisReport> cached = cachedAnalysisReport;
        if (isFresh(cached, currentCount, ANALYSIS_MAX_CACHE_MS)) {
            return cached.value;
        }
        synchronized (analysisLock) {
            cached = cachedAnalysisReport;
            if (isFresh(cached, currentCount, ANALYSIS_MAX_CACHE_MS)) {
                return cached.value;
            }
            long generation = cacheGeneration.get();
            AnalysisReport report = computeAnalysisReport();
            cachedAnalysisReport = new Cached<>(report, currentCount, generation);
            return report;
        }
    }

    private AnalysisReport computeAnalysisReport() {
        AnalysisReport report = new AnalysisReport();
        report.generatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        long total = 0;
        long[] severityCounts = new long[Severity.values().length];
        Map<String, Integer> serverCounts = new HashMap<>();
        for (IssueGroupCount group : issueStore.getIssueGroupCounts()) {
            total += group.count;
            severityCounts[group.severity.ordinal()] += group.count;
            String server = group.serverName != null ? group.serverName : "Unknown";
            serverCounts.merge(server, (int) group.count, Integer::sum);
        }
        report.totalIssuesAnalyzed = (int) total;

        // Severity distribution
        report.severityDistribution = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            long count = severityCounts[s.ordinal()];
            double percentage = total == 0 ? 0 : (count * 100.0 / total);
            report.severityDistribution.put(s.name(), new SeverityStats((int) count, percentage));
        }

        // Server health from aggregated counts
        report.serverHealthScores = calculateServerHealthFromCounts(serverCounts);

        // Most recent issues (newest first) for pattern-based analysis
        List<LogIssue> sample = issueStore.getIssues(0, ANALYSIS_SAMPLE_SIZE);

        Map<Integer, Integer> hourCounts = new HashMap<>();
        for (LogIssue issue : sample) {
            hourCounts.merge(issue.getDetectedAt().getHour(), 1, Integer::sum);
        }
        report.peakHours = hourCounts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        report.commonPatterns = findCommonPatterns(sample, 10);

        List<LogIssue> recent = sample.size() > RECURRING_SAMPLE_SIZE
            ? sample.subList(0, RECURRING_SAMPLE_SIZE) : sample;
        report.recurringIssues = detectRecurringIssues(recent, 3);
        report.rootCauseCandidates = identifyRootCauseCandidates(recent);

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
     */
    public List<Anomaly> detectAnomalies() {
        int currentCount = issueStore.getCurrentIssuesCount();
        Cached<List<Anomaly>> cached = cachedAnomalies;
        if (isFresh(cached, currentCount, MAX_CACHE_MS)) {
            return cached.value;
        }
        synchronized (anomalyLock) {
            cached = cachedAnomalies;
            if (isFresh(cached, currentCount, MAX_CACHE_MS)) {
                return cached.value;
            }
            long generation = cacheGeneration.get();
            List<Anomaly> anomalies = computeAnomalies();
            cachedAnomalies = new Cached<>(anomalies, currentCount, generation);
            return anomalies;
        }
    }

    private List<Anomaly> computeAnomalies() {
        List<Anomaly> anomalies = new ArrayList<>();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff5Min = now.minusMinutes(5);
        LocalDateTime cutoff30Min = now.minusMinutes(30);

        int previous30MinCount = issueStore.getDetectionTimesSince(cutoff30Min).size();

        List<LogIssue> last5Min = new ArrayList<>();
        Map<String, Integer> recentTypeCounts = new HashMap<>();
        Map<String, Integer> serverCountsLast5Min = new HashMap<>();
        for (LogIssue issue : issueStore.getRecentIssues(5)) {
            if (!issue.getDetectedAt().isAfter(cutoff5Min)) {
                continue;
            }
            last5Min.add(issue);
            recentTypeCounts.merge(issue.getIssueType(), 1, Integer::sum);
            String server = issue.getServerName() != null ? issue.getServerName() : "Unknown";
            serverCountsLast5Min.merge(server, 1, Integer::sum);
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

        // Check for new exception types: every occurrence of the type is within the last 5 minutes
        if (!recentTypeCounts.isEmpty()) {
            Map<String, Long> totalTypeCounts = new HashMap<>();
            for (IssueGroupCount group : issueStore.getIssueGroupCounts()) {
                if (recentTypeCounts.containsKey(group.issueType)) {
                    totalTypeCounts.merge(group.issueType, group.count, Long::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : recentTypeCounts.entrySet()) {
                if (totalTypeCounts.getOrDefault(entry.getKey(), 0L) <= entry.getValue()) {
                    Anomaly newException = new Anomaly();
                    newException.type = "NEW_EXCEPTION";
                    newException.severity = "MEDIUM";
                    newException.description = "New exception type detected: " + entry.getKey();
                    newException.detectedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    anomalies.add(newException);
                }
            }
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

    private Map<String, Integer> calculateMinutelyTrend(List<LocalDateTime> times, LocalDateTime now, int minutes) {
        Map<String, Integer> trend = new LinkedHashMap<>();

        for (int i = minutes - 1; i >= 0; i--) {
            LocalDateTime minute = now.minusMinutes(i).truncatedTo(ChronoUnit.MINUTES);
            trend.put(minute.format(TIME_FORMATTER), 0);
        }

        LocalDateTime cutoff = now.minusMinutes(minutes);
        for (LocalDateTime detectedAt : times) {
            if (detectedAt.isAfter(cutoff)) {
                String key = detectedAt.truncatedTo(ChronoUnit.MINUTES).format(TIME_FORMATTER);
                if (trend.containsKey(key)) {
                    trend.merge(key, 1, Integer::sum);
                }
            }
        }

        return trend;
    }

    private Map<String, Integer> calculateHourlyTrend(List<LocalDateTime> times, LocalDateTime now, int hours) {
        Map<String, Integer> trend = new LinkedHashMap<>();

        for (int i = hours - 1; i >= 0; i--) {
            LocalDateTime hour = now.minusHours(i).truncatedTo(ChronoUnit.HOURS);
            trend.put(hour.format(DATE_FORMATTER), 0);
        }

        LocalDateTime cutoff = now.minusHours(hours);
        for (LocalDateTime detectedAt : times) {
            if (detectedAt.isAfter(cutoff)) {
                String key = detectedAt.truncatedTo(ChronoUnit.HOURS).format(DATE_FORMATTER);
                if (trend.containsKey(key)) {
                    trend.merge(key, 1, Integer::sum);
                }
            }
        }

        return trend;
    }

    private List<String> findCommonPatterns(List<LogIssue> issues, int limit) {
        Map<String, Integer> patterns = new HashMap<>();

        for (LogIssue issue : issues) {
            if (issue.getMessage() == null) {
                continue;
            }
            Matcher matcher = CLASS_NAME_PATTERN.matcher(issue.getMessage());
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
        if (message == null) {
            return "";
        }
        // Remove numbers, timestamps, and UUIDs
        String normalized = NORMALIZE_DATE.matcher(message).replaceAll("DATE");
        normalized = NORMALIZE_TIME.matcher(normalized).replaceAll("TIME");
        normalized = NORMALIZE_UUID.matcher(normalized).replaceAll("UUID");
        normalized = NORMALIZE_NUMBER.matcher(normalized).replaceAll("N");
        return normalized.trim();
    }

    private List<RootCauseCandidate> identifyRootCauseCandidates(List<LogIssue> issues) {
        List<RootCauseCandidate> candidates = new ArrayList<>();

        // Look for NullPointerExceptions with class names
        Map<String, Integer> nullPointerLocations = new HashMap<>();
        for (LogIssue issue : issues) {
            if (issue.getIssueType() != null && issue.getIssueType().contains("NullPointer")
                    && issue.getFullStackTrace() != null) {
                Matcher matcher = STACK_FRAME_PATTERN.matcher(issue.getFullStackTrace());
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
