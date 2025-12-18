package com.logdashboard.store;

import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2 file-based implementation of issue storage.
 * Provides persistent storage with indexed queries for better performance at scale.
 * 
 * Thread-safe implementation supporting concurrent access from multiple web requests.
 */
public class H2IssueStore implements IssueRepository {
    
    private final DatabaseManager dbManager;
    private final int maxIssues;
    private final List<IssueRepository.IssueListener> listeners;
    private final AtomicLong totalIssuesCount;
    
    // Cache for frequently accessed statistics
    private final Map<Severity, AtomicLong> severityCounts;
    private final Map<String, AtomicLong> serverCounts;
    
    private static final int MAX_LISTENERS = 100;
    private static final int MAX_FILTER_RESULTS = 10000;
    
    /**
     * Creates an H2IssueStore with the specified database manager and max issues limit.
     */
    public H2IssueStore(DatabaseManager dbManager, int maxIssues) {
        this.dbManager = dbManager;
        this.maxIssues = Math.min(maxIssues, 100000); // Higher limit since we use disk
        this.listeners = new CopyOnWriteArrayList<>();
        this.totalIssuesCount = new AtomicLong(0);
        this.severityCounts = new HashMap<>();
        this.serverCounts = new HashMap<>();
        
        for (Severity s : Severity.values()) {
            severityCounts.put(s, new AtomicLong(0));
        }
        
        // Initialize counters from database
        initializeCounters();
    }
    
    /**
     * Initializes counters from the database on startup.
     */
    private void initializeCounters() {
        try {
            Connection conn = dbManager.getConnection();
            
            // Load total issues received
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT stat_value FROM issue_statistics WHERE stat_key = 'total_issues_received'")) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    totalIssuesCount.set(rs.getLong(1));
                }
            }
            
            // Load severity counts from current data
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT severity, COUNT(*) FROM log_issues GROUP BY severity")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String sevStr = rs.getString(1);
                    try {
                        Severity sev = Severity.valueOf(sevStr);
                        severityCounts.get(sev).set(rs.getLong(2));
                    } catch (IllegalArgumentException e) {
                        // Ignore unknown severity
                    }
                }
            }
            
            // Load server counts
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT server_name, COUNT(*) FROM log_issues WHERE server_name IS NOT NULL GROUP BY server_name")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String serverName = rs.getString(1);
                    serverCounts.put(serverName, new AtomicLong(rs.getLong(2)));
                }
            }
            
            System.out.println("H2IssueStore initialized with " + getCurrentIssuesCount() + " issues from database.");
            
        } catch (SQLException e) {
            System.err.println("Error initializing counters from database: " + e.getMessage());
        }
    }
    
    /**
     * Adds a new issue to the store.
     */
    @Override
    public void addIssue(LogIssue issue) {
        try {
            Connection conn = dbManager.getConnection();
            
            // Insert the issue
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO log_issues (id, server_name, file_name, line_number, issue_type, " +
                    "message, full_stack_trace, detected_at, severity, acknowledged) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                
                stmt.setString(1, issue.getId());
                stmt.setString(2, issue.getServerName());
                stmt.setString(3, issue.getFileName());
                stmt.setInt(4, issue.getLineNumber());
                stmt.setString(5, issue.getIssueType());
                stmt.setString(6, issue.getMessage());
                stmt.setString(7, issue.getFullStackTrace());
                stmt.setTimestamp(8, Timestamp.valueOf(issue.getDetectedAt()));
                stmt.setString(9, issue.getSeverity().name());
                stmt.setBoolean(10, issue.isAcknowledged());
                
                stmt.executeUpdate();
            }
            
            // Update total count
            totalIssuesCount.incrementAndGet();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE issue_statistics SET stat_value = stat_value + 1 WHERE stat_key = 'total_issues_received'")) {
                stmt.executeUpdate();
            }
            
            // Update cached counters
            severityCounts.computeIfAbsent(issue.getSeverity(), k -> new AtomicLong(0)).incrementAndGet();
            String serverName = issue.getServerName() != null ? issue.getServerName() : "Unknown";
            serverCounts.computeIfAbsent(serverName, k -> new AtomicLong(0)).incrementAndGet();
            
            // Trim old issues if exceeded max
            trimOldIssues(conn);
            
            // Notify listeners
            for (IssueRepository.IssueListener listener : listeners) {
                try {
                    listener.onNewIssue(issue);
                } catch (Exception e) {
                    System.err.println("Error notifying listener: " + e.getMessage());
                }
            }
            
        } catch (SQLException e) {
            System.err.println("Error adding issue to database: " + e.getMessage());
        }
    }
    
    /**
     * Trims old issues when max limit is exceeded.
     */
    private void trimOldIssues(Connection conn) throws SQLException {
        int currentCount = getCurrentIssuesCount();
        if (currentCount > maxIssues) {
            int toDelete = currentCount - maxIssues;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM log_issues WHERE id IN " +
                    "(SELECT id FROM log_issues ORDER BY detected_at ASC LIMIT ?)")) {
                stmt.setInt(1, toDelete);
                stmt.executeUpdate();
            }
        }
    }
    
    /**
     * Adds a listener for new issues.
     */
    @Override
    public boolean addListener(IssueRepository.IssueListener listener) {
        if (listeners.size() >= MAX_LISTENERS) {
            System.err.println("Warning: Max listeners limit (" + MAX_LISTENERS + ") reached");
            return false;
        }
        listeners.add(listener);
        return true;
    }
    
    /**
     * Removes a listener.
     */
    @Override
    public void removeListener(IssueRepository.IssueListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Gets all issues (most recent first).
     */
    @Override
    public List<LogIssue> getAllIssues() {
        return getIssues(0, maxIssues);
    }
    
    /**
     * Gets issues with pagination.
     */
    @Override
    public List<LogIssue> getIssues(int offset, int limit) {
        List<LogIssue> issues = new ArrayList<>();
        try {
            Connection conn = dbManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT * FROM log_issues ORDER BY detected_at DESC LIMIT ? OFFSET ?")) {
                stmt.setInt(1, limit);
                stmt.setInt(2, offset);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    issues.add(resultSetToIssue(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting issues: " + e.getMessage());
        }
        return issues;
    }
    
    /**
     * Gets issues filtered by severity.
     */
    @Override
    public List<LogIssue> getIssuesBySeverity(Severity severity) {
        List<LogIssue> issues = new ArrayList<>();
        try {
            Connection conn = dbManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT * FROM log_issues WHERE severity = ? ORDER BY detected_at DESC")) {
                stmt.setString(1, severity.name());
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    issues.add(resultSetToIssue(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting issues by severity: " + e.getMessage());
        }
        return issues;
    }
    
    /**
     * Gets issues filtered by server.
     */
    @Override
    public List<LogIssue> getIssuesByServer(String serverName) {
        List<LogIssue> issues = new ArrayList<>();
        try {
            Connection conn = dbManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT * FROM log_issues WHERE server_name = ? ORDER BY detected_at DESC")) {
                stmt.setString(1, serverName);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    issues.add(resultSetToIssue(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting issues by server: " + e.getMessage());
        }
        return issues;
    }
    
    /**
     * Gets issues from the last N minutes.
     */
    @Override
    public List<LogIssue> getRecentIssues(int minutes) {
        List<LogIssue> issues = new ArrayList<>();
        try {
            Connection conn = dbManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT * FROM log_issues WHERE detected_at > ? ORDER BY detected_at DESC")) {
                stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now().minusMinutes(minutes)));
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    issues.add(resultSetToIssue(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting recent issues: " + e.getMessage());
        }
        return issues;
    }
    
    /**
     * Gets issues within a date range.
     */
    @Override
    public List<LogIssue> getIssuesByDateRange(LocalDateTime from, LocalDateTime to) {
        return getFilteredIssues(null, null, from, to, 0, MAX_FILTER_RESULTS);
    }
    
    /**
     * Gets issues with combined filters.
     */
    @Override
    public List<LogIssue> getFilteredIssues(Severity severity, String serverName,
                                             LocalDateTime from, LocalDateTime to,
                                             int offset, int limit) {
        int effectiveLimit = Math.min(limit, MAX_FILTER_RESULTS);
        int effectiveOffset = Math.max(0, offset);
        
        List<LogIssue> issues = new ArrayList<>();
        try {
            Connection conn = dbManager.getConnection();
            
            StringBuilder sql = new StringBuilder("SELECT * FROM log_issues WHERE 1=1");
            List<Object> params = new ArrayList<>();
            
            if (severity != null) {
                sql.append(" AND severity = ?");
                params.add(severity.name());
            }
            if (serverName != null && !serverName.isEmpty()) {
                sql.append(" AND server_name = ?");
                params.add(serverName);
            }
            if (from != null) {
                sql.append(" AND detected_at >= ?");
                params.add(Timestamp.valueOf(from));
            }
            if (to != null) {
                sql.append(" AND detected_at <= ?");
                params.add(Timestamp.valueOf(to));
            }
            
            sql.append(" ORDER BY detected_at DESC LIMIT ? OFFSET ?");
            params.add(effectiveLimit);
            params.add(effectiveOffset);
            
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object param = params.get(i);
                    if (param instanceof String) {
                        stmt.setString(i + 1, (String) param);
                    } else if (param instanceof Timestamp) {
                        stmt.setTimestamp(i + 1, (Timestamp) param);
                    } else if (param instanceof Integer) {
                        stmt.setInt(i + 1, (Integer) param);
                    }
                }
                
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    issues.add(resultSetToIssue(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting filtered issues: " + e.getMessage());
        }
        return issues;
    }
    
    /**
     * Gets total count of filtered issues.
     */
    @Override
    public long getFilteredIssuesCount(Severity severity, String serverName,
                                        LocalDateTime from, LocalDateTime to) {
        try {
            Connection conn = dbManager.getConnection();
            
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM log_issues WHERE 1=1");
            List<Object> params = new ArrayList<>();
            
            if (severity != null) {
                sql.append(" AND severity = ?");
                params.add(severity.name());
            }
            if (serverName != null && !serverName.isEmpty()) {
                sql.append(" AND server_name = ?");
                params.add(serverName);
            }
            if (from != null) {
                sql.append(" AND detected_at >= ?");
                params.add(Timestamp.valueOf(from));
            }
            if (to != null) {
                sql.append(" AND detected_at <= ?");
                params.add(Timestamp.valueOf(to));
            }
            
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object param = params.get(i);
                    if (param instanceof String) {
                        stmt.setString(i + 1, (String) param);
                    } else if (param instanceof Timestamp) {
                        stmt.setTimestamp(i + 1, (Timestamp) param);
                    }
                }
                
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting filtered count: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Gets the earliest issue timestamp.
     */
    @Override
    public Optional<LocalDateTime> getEarliestIssueTime() {
        try {
            Connection conn = dbManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT MIN(detected_at) FROM log_issues")) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    if (ts != null) {
                        return Optional.of(ts.toLocalDateTime());
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting earliest time: " + e.getMessage());
        }
        return Optional.empty();
    }
    
    /**
     * Gets the latest issue timestamp.
     */
    @Override
    public Optional<LocalDateTime> getLatestIssueTime() {
        try {
            Connection conn = dbManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT MAX(detected_at) FROM log_issues")) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    if (ts != null) {
                        return Optional.of(ts.toLocalDateTime());
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting latest time: " + e.getMessage());
        }
        return Optional.empty();
    }
    
    /**
     * Gets issues grouped by date for trending.
     */
    @Override
    public Map<String, Integer> getDailyTrend(int days) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> trend = new LinkedHashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        
        // Initialize all days
        for (int i = days - 1; i >= 0; i--) {
            LocalDateTime day = now.minusDays(i).truncatedTo(ChronoUnit.DAYS);
            trend.put(day.format(formatter), 0);
        }
        
        try {
            Connection conn = dbManager.getConnection();
            LocalDateTime cutoff = now.minusDays(days - 1).truncatedTo(ChronoUnit.DAYS);
            
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT FORMATDATETIME(detected_at, 'MM-dd') as day, COUNT(*) " +
                    "FROM log_issues WHERE detected_at >= ? GROUP BY day")) {
                stmt.setTimestamp(1, Timestamp.valueOf(cutoff));
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String day = rs.getString(1);
                    if (trend.containsKey(day)) {
                        trend.put(day, rs.getInt(2));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting daily trend: " + e.getMessage());
        }
        
        return trend;
    }
    
    /**
     * Gets issues grouped by hour for trending.
     */
    @Override
    public Map<String, Integer> getHourlyTrend(int hours) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> trend = new LinkedHashMap<>();
        
        // Initialize all hours
        for (int i = hours - 1; i >= 0; i--) {
            LocalDateTime hour = now.minusHours(i).truncatedTo(ChronoUnit.HOURS);
            trend.put(hour.getHour() + ":00", 0);
        }
        
        try {
            Connection conn = dbManager.getConnection();
            LocalDateTime cutoff = now.minusHours(hours);
            
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT HOUR(detected_at) as hr, COUNT(*) " +
                    "FROM log_issues WHERE detected_at > ? GROUP BY hr")) {
                stmt.setTimestamp(1, Timestamp.valueOf(cutoff));
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String key = rs.getInt(1) + ":00";
                    trend.merge(key, rs.getInt(2), Integer::sum);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting hourly trend: " + e.getMessage());
        }
        
        return trend;
    }
    
    /**
     * Gets an issue by ID.
     */
    @Override
    public Optional<LogIssue> getIssueById(String id) {
        try {
            Connection conn = dbManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT * FROM log_issues WHERE id = ?")) {
                stmt.setString(1, id);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(resultSetToIssue(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting issue by id: " + e.getMessage());
        }
        return Optional.empty();
    }
    
    /**
     * Acknowledges an issue.
     */
    @Override
    public boolean acknowledgeIssue(String id) {
        try {
            Connection conn = dbManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE log_issues SET acknowledged = TRUE WHERE id = ?")) {
                stmt.setString(1, id);
                return stmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            System.err.println("Error acknowledging issue: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Clears all issues.
     */
    @Override
    public void clearAll() {
        try {
            Connection conn = dbManager.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM log_issues");
            }
            // Reset cached counters
            for (AtomicLong count : severityCounts.values()) {
                count.set(0);
            }
            serverCounts.clear();
        } catch (SQLException e) {
            System.err.println("Error clearing issues: " + e.getMessage());
        }
    }
    
    /**
     * Clears acknowledged issues.
     */
    @Override
    public void clearAcknowledged() {
        try {
            Connection conn = dbManager.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM log_issues WHERE acknowledged = TRUE");
            }
            // Refresh counters
            initializeCounters();
        } catch (SQLException e) {
            System.err.println("Error clearing acknowledged: " + e.getMessage());
        }
    }
    
    /**
     * Gets the total count of issues ever received.
     */
    @Override
    public long getTotalIssuesCount() {
        return totalIssuesCount.get();
    }
    
    /**
     * Gets the current number of issues in the store.
     */
    @Override
    public int getCurrentIssuesCount() {
        try {
            Connection conn = dbManager.getConnection();
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM log_issues");
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting count: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Gets count by severity.
     */
    @Override
    public long getCountBySeverity(Severity severity) {
        AtomicLong count = severityCounts.get(severity);
        return count != null ? count.get() : 0;
    }
    
    /**
     * Gets counts by server.
     */
    @Override
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
    @Override
    public Map<String, Integer> getCurrentSeverityDistribution() {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            distribution.put(s.name(), 0);
        }
        
        try {
            Connection conn = dbManager.getConnection();
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                    "SELECT severity, COUNT(*) FROM log_issues GROUP BY severity");
                while (rs.next()) {
                    distribution.put(rs.getString(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting distribution: " + e.getMessage());
        }
        
        return distribution;
    }
    
    /**
     * Gets unique server names from current issues.
     */
    @Override
    public Set<String> getActiveServers() {
        Set<String> servers = new HashSet<>();
        try {
            Connection conn = dbManager.getConnection();
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                    "SELECT DISTINCT server_name FROM log_issues WHERE server_name IS NOT NULL");
                while (rs.next()) {
                    servers.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting servers: " + e.getMessage());
        }
        return servers;
    }
    
    /**
     * Gets unique exception types from current issues.
     */
    @Override
    public Map<String, Integer> getExceptionTypeDistribution() {
        Map<String, Integer> distribution = new HashMap<>();
        try {
            Connection conn = dbManager.getConnection();
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                    "SELECT issue_type, COUNT(*) as cnt FROM log_issues " +
                    "GROUP BY issue_type ORDER BY cnt DESC LIMIT 10");
                while (rs.next()) {
                    distribution.put(rs.getString(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting exception types: " + e.getMessage());
        }
        return distribution;
    }
    
    /**
     * Converts a ResultSet row to a LogIssue object.
     */
    private LogIssue resultSetToIssue(ResultSet rs) throws SQLException {
        String serverName = rs.getString("server_name");
        String fileName = rs.getString("file_name");
        int lineNumber = rs.getInt("line_number");
        String issueType = rs.getString("issue_type");
        String message = rs.getString("message");
        String stackTrace = rs.getString("full_stack_trace");
        String severityStr = rs.getString("severity");
        boolean acknowledged = rs.getBoolean("acknowledged");
        Timestamp detectedAt = rs.getTimestamp("detected_at");
        String id = rs.getString("id");
        
        Severity severity;
        try {
            severity = Severity.valueOf(severityStr);
        } catch (IllegalArgumentException e) {
            severity = Severity.ERROR;
        }
        
        // Create issue with stored values
        LogIssue issue = new LogIssue(serverName, fileName, lineNumber, issueType, 
                                       message, stackTrace, severity, 
                                       detectedAt.toLocalDateTime(), id);
        issue.setAcknowledged(acknowledged);
        return issue;
    }
    
    /**
     * Closes the database connection.
     */
    public void close() {
        dbManager.close();
    }
}
