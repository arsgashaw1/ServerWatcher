package com.logdashboard.store;

import com.logdashboard.model.DumpFileTracking;
import com.logdashboard.model.DumpProcessConfig;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Store for managing database dump processing configurations and file tracking.
 * Uses H2 database for persistence.
 */
public class DumpProcessingStore {
    
    private final DatabaseManager databaseManager;
    
    public DumpProcessingStore(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * Initializes the dump processing tables in the database.
     */
    public void initialize() throws SQLException {
        Connection conn = databaseManager.getConnection();
        try (Statement stmt = conn.createStatement()) {
            // Create dump_process_config table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dump_process_config (" +
                "    id INT AUTO_INCREMENT PRIMARY KEY," +
                "    server_name VARCHAR(255) NOT NULL," +
                "    db_folder VARCHAR(500) NOT NULL," +
                "    dump_folder VARCHAR(500) NOT NULL," +
                "    db_type VARCHAR(50) NOT NULL," +
                "    java_path VARCHAR(500) NOT NULL," +
                "    threshold_minutes INT DEFAULT 1," +
                "    admin_user VARCHAR(100)," +
                "    enabled BOOLEAN DEFAULT TRUE," +
                "    last_run_time VARCHAR(50)," +
                "    last_run_status VARCHAR(50)," +
                "    last_run_output CLOB," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")");
            
            // Create dump_file_tracking table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dump_file_tracking (" +
                "    id INT AUTO_INCREMENT PRIMARY KEY," +
                "    config_id INT NOT NULL," +
                "    file_name VARCHAR(255) NOT NULL," +
                "    file_path VARCHAR(500) NOT NULL," +
                "    file_size BIGINT," +
                "    first_seen_time BIGINT NOT NULL," +
                "    last_modified_time BIGINT," +
                "    status VARCHAR(50) DEFAULT 'PENDING'," +
                "    process_start_time VARCHAR(50)," +
                "    process_end_time VARCHAR(50)," +
                "    process_output CLOB," +
                "    retry_count INT DEFAULT 0," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    FOREIGN KEY (config_id) REFERENCES dump_process_config(id) ON DELETE CASCADE" +
                ")");
            
            // Create index for faster lookups
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_dump_file_status ON dump_file_tracking(config_id, status)");
            
            System.out.println("Dump processing tables initialized.");
        }
    }
    
    // ==================== DumpProcessConfig Operations ====================
    
    /**
     * Gets all dump process configurations.
     */
    public List<DumpProcessConfig> getAllConfigs() {
        List<DumpProcessConfig> configs = new ArrayList<>();
        String sql = "SELECT id, server_name, db_folder, dump_folder, db_type, java_path, " +
                     "threshold_minutes, admin_user, enabled, last_run_time, last_run_status, last_run_output " +
                     "FROM dump_process_config ORDER BY id";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                configs.add(mapResultSetToConfig(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting dump process configs: " + e.getMessage());
        }
        
        return configs;
    }
    
    /**
     * Gets all enabled dump process configurations.
     */
    public List<DumpProcessConfig> getEnabledConfigs() {
        List<DumpProcessConfig> configs = new ArrayList<>();
        String sql = "SELECT id, server_name, db_folder, dump_folder, db_type, java_path, " +
                     "threshold_minutes, admin_user, enabled, last_run_time, last_run_status, last_run_output " +
                     "FROM dump_process_config WHERE enabled = TRUE ORDER BY id";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                configs.add(mapResultSetToConfig(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting enabled dump process configs: " + e.getMessage());
        }
        
        return configs;
    }
    
    /**
     * Gets a dump process config by ID.
     */
    public Optional<DumpProcessConfig> getConfigById(int id) {
        String sql = "SELECT id, server_name, db_folder, dump_folder, db_type, java_path, " +
                     "threshold_minutes, admin_user, enabled, last_run_time, last_run_status, last_run_output " +
                     "FROM dump_process_config WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToConfig(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting dump process config by ID: " + e.getMessage());
        }
        
        return Optional.empty();
    }
    
    /**
     * Adds a new dump process config.
     */
    public DumpProcessConfig addConfig(DumpProcessConfig config) throws SQLException {
        String sql = "INSERT INTO dump_process_config (server_name, db_folder, dump_folder, db_type, " +
                     "java_path, threshold_minutes, admin_user, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql, 
                Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, config.getServerName());
            stmt.setString(2, config.getDbFolder());
            stmt.setString(3, config.getDumpFolder());
            stmt.setString(4, config.getDbType());
            stmt.setString(5, config.getJavaPath());
            stmt.setInt(6, config.getThresholdMinutes());
            stmt.setString(7, config.getAdminUser());
            stmt.setBoolean(8, config.isEnabled());
            
            stmt.executeUpdate();
            
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    config.setId(rs.getInt(1));
                }
            }
        }
        
        return config;
    }
    
    /**
     * Updates an existing dump process config.
     */
    public boolean updateConfig(DumpProcessConfig config) throws SQLException {
        String sql = "UPDATE dump_process_config SET server_name = ?, db_folder = ?, dump_folder = ?, " +
                     "db_type = ?, java_path = ?, threshold_minutes = ?, admin_user = ?, enabled = ?, " +
                     "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, config.getServerName());
            stmt.setString(2, config.getDbFolder());
            stmt.setString(3, config.getDumpFolder());
            stmt.setString(4, config.getDbType());
            stmt.setString(5, config.getJavaPath());
            stmt.setInt(6, config.getThresholdMinutes());
            stmt.setString(7, config.getAdminUser());
            stmt.setBoolean(8, config.isEnabled());
            stmt.setInt(9, config.getId());
            
            return stmt.executeUpdate() > 0;
        }
    }
    
    /**
     * Updates the last run status of a config.
     */
    public void updateLastRunStatus(int configId, String status, String output) throws SQLException {
        String sql = "UPDATE dump_process_config SET last_run_time = ?, last_run_status = ?, " +
                     "last_run_output = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, Instant.now().toString());
            stmt.setString(2, status);
            stmt.setString(3, truncateOutput(output));
            stmt.setInt(4, configId);
            
            stmt.executeUpdate();
        }
    }
    
    /**
     * Deletes a dump process config by ID.
     * Also deletes associated file tracking records (via CASCADE).
     */
    public boolean deleteConfig(int id) throws SQLException {
        String sql = "DELETE FROM dump_process_config WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        }
    }
    
    // ==================== DumpFileTracking Operations ====================
    
    /**
     * Gets all file tracking records for a config.
     */
    public List<DumpFileTracking> getFilesForConfig(int configId) {
        List<DumpFileTracking> files = new ArrayList<>();
        String sql = "SELECT id, config_id, file_name, file_path, file_size, first_seen_time, " +
                     "last_modified_time, status, process_start_time, process_end_time, process_output, retry_count " +
                     "FROM dump_file_tracking WHERE config_id = ? ORDER BY first_seen_time DESC";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, configId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    files.add(mapResultSetToFileTracking(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting files for config: " + e.getMessage());
        }
        
        return files;
    }
    
    /**
     * Gets pending files for a config that are ready for processing.
     */
    public List<DumpFileTracking> getPendingFiles(int configId) {
        List<DumpFileTracking> files = new ArrayList<>();
        String sql = "SELECT id, config_id, file_name, file_path, file_size, first_seen_time, " +
                     "last_modified_time, status, process_start_time, process_end_time, process_output, retry_count " +
                     "FROM dump_file_tracking WHERE config_id = ? AND status = 'PENDING' " +
                     "ORDER BY first_seen_time ASC";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, configId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    files.add(mapResultSetToFileTracking(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting pending files: " + e.getMessage());
        }
        
        return files;
    }
    
    /**
     * Gets all files currently being processed.
     */
    public List<DumpFileTracking> getProcessingFiles() {
        List<DumpFileTracking> files = new ArrayList<>();
        String sql = "SELECT id, config_id, file_name, file_path, file_size, first_seen_time, " +
                     "last_modified_time, status, process_start_time, process_end_time, process_output, retry_count " +
                     "FROM dump_file_tracking WHERE status = 'PROCESSING' ORDER BY process_start_time ASC";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                files.add(mapResultSetToFileTracking(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting processing files: " + e.getMessage());
        }
        
        return files;
    }
    
    /**
     * Finds a file tracking record by config ID and file path.
     */
    public Optional<DumpFileTracking> findFileByPath(int configId, String filePath) {
        String sql = "SELECT id, config_id, file_name, file_path, file_size, first_seen_time, " +
                     "last_modified_time, status, process_start_time, process_end_time, process_output, retry_count " +
                     "FROM dump_file_tracking WHERE config_id = ? AND file_path = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, configId);
            stmt.setString(2, filePath);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToFileTracking(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error finding file by path: " + e.getMessage());
        }
        
        return Optional.empty();
    }
    
    /**
     * Adds a new file tracking record.
     */
    public DumpFileTracking addFileTracking(DumpFileTracking file) throws SQLException {
        String sql = "INSERT INTO dump_file_tracking (config_id, file_name, file_path, file_size, " +
                     "first_seen_time, last_modified_time, status) VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql, 
                Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, file.getConfigId());
            stmt.setString(2, file.getFileName());
            stmt.setString(3, file.getFilePath());
            stmt.setLong(4, file.getFileSize());
            stmt.setLong(5, file.getFirstSeenTime());
            stmt.setLong(6, file.getLastModifiedTime());
            stmt.setString(7, file.getStatus());
            
            stmt.executeUpdate();
            
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    file.setId(rs.getInt(1));
                }
            }
        }
        
        return file;
    }
    
    /**
     * Updates file tracking status.
     */
    public void updateFileStatus(int fileId, String status, String output) throws SQLException {
        String sql;
        if (DumpFileTracking.STATUS_PROCESSING.equals(status)) {
            sql = "UPDATE dump_file_tracking SET status = ?, process_start_time = ? WHERE id = ?";
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
                stmt.setString(1, status);
                stmt.setString(2, Instant.now().toString());
                stmt.setInt(3, fileId);
                stmt.executeUpdate();
            }
        } else {
            sql = "UPDATE dump_file_tracking SET status = ?, process_end_time = ?, process_output = ? WHERE id = ?";
            try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
                stmt.setString(1, status);
                stmt.setString(2, Instant.now().toString());
                stmt.setString(3, truncateOutput(output));
                stmt.setInt(4, fileId);
                stmt.executeUpdate();
            }
        }
    }
    
    /**
     * Increments the retry count for a file.
     */
    public void incrementRetryCount(int fileId) throws SQLException {
        String sql = "UPDATE dump_file_tracking SET retry_count = retry_count + 1, status = 'PENDING' WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, fileId);
            stmt.executeUpdate();
        }
    }
    
    /**
     * Deletes a file tracking record.
     */
    public boolean deleteFileTracking(int fileId) throws SQLException {
        String sql = "DELETE FROM dump_file_tracking WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, fileId);
            return stmt.executeUpdate() > 0;
        }
    }
    
    /**
     * Deletes completed file tracking records older than the specified hours.
     */
    public int cleanupOldRecords(int hoursOld) throws SQLException {
        long cutoffTime = System.currentTimeMillis() - (hoursOld * 60 * 60 * 1000L);
        String sql = "DELETE FROM dump_file_tracking WHERE status IN ('COMPLETED', 'FAILED') " +
                     "AND first_seen_time < ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setLong(1, cutoffTime);
            return stmt.executeUpdate();
        }
    }
    
    /**
     * Resets files stuck in PROCESSING status back to PENDING.
     * This is called on startup to recover from server crashes/restarts.
     */
    public int resetStuckProcessingFiles() throws SQLException {
        String sql = "UPDATE dump_file_tracking SET status = 'PENDING', " +
                     "process_output = CONCAT(COALESCE(process_output, ''), '\n[Server restart - reset to PENDING]') " +
                     "WHERE status = 'PROCESSING'";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            return stmt.executeUpdate();
        }
    }
    
    /**
     * Gets processing statistics for a config.
     */
    public ProcessingStats getStatsForConfig(int configId) {
        ProcessingStats stats = new ProcessingStats();
        String sql = "SELECT status, COUNT(*) as count FROM dump_file_tracking " +
                     "WHERE config_id = ? GROUP BY status";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, configId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String status = rs.getString("status");
                    int count = rs.getInt("count");
                    switch (status) {
                        case DumpFileTracking.STATUS_PENDING:
                            stats.pendingCount = count;
                            break;
                        case DumpFileTracking.STATUS_PROCESSING:
                            stats.processingCount = count;
                            break;
                        case DumpFileTracking.STATUS_COMPLETED:
                            stats.completedCount = count;
                            break;
                        case DumpFileTracking.STATUS_FAILED:
                            stats.failedCount = count;
                            break;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting stats for config: " + e.getMessage());
        }
        
        return stats;
    }
    
    // ==================== Helper Methods ====================
    
    private DumpProcessConfig mapResultSetToConfig(ResultSet rs) throws SQLException {
        DumpProcessConfig config = new DumpProcessConfig();
        config.setId(rs.getInt("id"));
        config.setServerName(rs.getString("server_name"));
        config.setDbFolder(rs.getString("db_folder"));
        config.setDumpFolder(rs.getString("dump_folder"));
        config.setDbType(rs.getString("db_type"));
        config.setJavaPath(rs.getString("java_path"));
        config.setThresholdMinutes(rs.getInt("threshold_minutes"));
        config.setAdminUser(rs.getString("admin_user"));
        config.setEnabled(rs.getBoolean("enabled"));
        config.setLastRunTime(rs.getString("last_run_time"));
        config.setLastRunStatus(rs.getString("last_run_status"));
        config.setLastRunOutput(rs.getString("last_run_output"));
        return config;
    }
    
    private DumpFileTracking mapResultSetToFileTracking(ResultSet rs) throws SQLException {
        DumpFileTracking file = new DumpFileTracking();
        file.setId(rs.getInt("id"));
        file.setConfigId(rs.getInt("config_id"));
        file.setFileName(rs.getString("file_name"));
        file.setFilePath(rs.getString("file_path"));
        file.setFileSize(rs.getLong("file_size"));
        file.setFirstSeenTime(rs.getLong("first_seen_time"));
        file.setLastModifiedTime(rs.getLong("last_modified_time"));
        file.setStatus(rs.getString("status"));
        file.setProcessStartTime(rs.getString("process_start_time"));
        file.setProcessEndTime(rs.getString("process_end_time"));
        file.setProcessOutput(rs.getString("process_output"));
        file.setRetryCount(rs.getInt("retry_count"));
        return file;
    }
    
    /**
     * Truncates output to prevent storing very large outputs.
     */
    private String truncateOutput(String output) {
        if (output == null) return null;
        int maxLength = 50000; // 50KB max
        if (output.length() > maxLength) {
            return output.substring(0, maxLength) + "\n... [truncated, " + output.length() + " total chars]";
        }
        return output;
    }
    
    /**
     * Helper class for processing statistics.
     */
    public static class ProcessingStats {
        public int pendingCount = 0;
        public int processingCount = 0;
        public int completedCount = 0;
        public int failedCount = 0;
        
        public int getTotalCount() {
            return pendingCount + processingCount + completedCount + failedCount;
        }
    }
}
