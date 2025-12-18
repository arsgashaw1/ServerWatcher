package com.logdashboard.store;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages H2 database connections and schema initialization.
 * Uses file-based storage for data persistence across restarts.
 */
public class DatabaseManager {
    
    private static final String DEFAULT_DB_NAME = "log-dashboard";
    private static final String SCHEMA_VERSION = "1";
    
    private final String jdbcUrl;
    private final String dbPath;
    private Connection connection;
    
    /**
     * Creates a DatabaseManager with the default database location.
     * Database will be stored in the same directory as the JAR file under data/log-dashboard
     */
    public DatabaseManager() {
        this(Paths.get(getJarDirectory(), "data", DEFAULT_DB_NAME).toString());
    }
    
    /**
     * Gets the directory where the JAR file (or class files) is located.
     * 
     * @return Path to the directory containing the application
     */
    private static String getJarDirectory() {
        try {
            // Get the location of the DatabaseManager class
            File jarFile = new File(DatabaseManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            
            // If it's a JAR file, get its parent directory
            // If it's a directory (running from IDE), use it directly
            if (jarFile.isFile()) {
                return jarFile.getParentFile().getAbsolutePath();
            } else {
                return jarFile.getAbsolutePath();
            }
        } catch (URISyntaxException e) {
            System.err.println("Could not determine JAR location, using current directory: " + e.getMessage());
            return ".";
        }
    }
    
    /**
     * Creates a DatabaseManager with a custom database path.
     * 
     * @param dbPath Path to the database file (without .mv.db extension)
     */
    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
        // H2 file-based URL with auto-server mode disabled for embedded use
        // Using MVCC for better concurrent access
        this.jdbcUrl = "jdbc:h2:file:" + dbPath + 
                ";MODE=MySQL" +
                ";AUTO_RECONNECT=TRUE" +
                ";DB_CLOSE_DELAY=-1" +
                ";DB_CLOSE_ON_EXIT=FALSE";
    }
    
    /**
     * Initializes the database connection and creates schema if needed.
     */
    public synchronized void initialize() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        
        // Ensure data directory exists
        Path dataDir = Paths.get(dbPath).getParent();
        if (dataDir != null) {
            dataDir.toFile().mkdirs();
        }
        
        connection = DriverManager.getConnection(jdbcUrl, "sa", "");
        createSchema();
        
        System.out.println("H2 Database initialized at: " + dbPath);
    }
    
    /**
     * Creates the database schema if it doesn't exist.
     */
    private void createSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Create log_issues table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS log_issues (" +
                "    id VARCHAR(64) PRIMARY KEY," +
                "    server_name VARCHAR(255)," +
                "    file_name VARCHAR(1024)," +
                "    line_number INT," +
                "    issue_type VARCHAR(255)," +
                "    message TEXT," +
                "    full_stack_trace TEXT," +
                "    detected_at TIMESTAMP NOT NULL," +
                "    severity VARCHAR(32) NOT NULL," +
                "    acknowledged BOOLEAN DEFAULT FALSE," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")");
            
            // Create indexes for common query patterns
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_issues_detected_at " +
                "ON log_issues(detected_at DESC)");
            
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_issues_severity " +
                "ON log_issues(severity)");
            
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_issues_server " +
                "ON log_issues(server_name)");
            
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_issues_severity_detected " +
                "ON log_issues(severity, detected_at DESC)");
            
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_issues_server_detected " +
                "ON log_issues(server_name, detected_at DESC)");
            
            // Create statistics table for tracking totals
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS issue_statistics (" +
                "    stat_key VARCHAR(128) PRIMARY KEY," +
                "    stat_value BIGINT DEFAULT 0" +
                ")");
            
            // Initialize total count if not exists
            stmt.execute(
                "MERGE INTO issue_statistics (stat_key, stat_value) " +
                "KEY (stat_key) " +
                "VALUES ('total_issues_received', 0)");
            
            // Create schema version table for future migrations
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS schema_version (" +
                "    version VARCHAR(32) PRIMARY KEY," +
                "    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")");
            
            // Record current schema version
            stmt.execute(
                "MERGE INTO schema_version (version) KEY (version) VALUES ('" + SCHEMA_VERSION + "')");
        }
    }
    
    /**
     * Gets a database connection.
     */
    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            initialize();
        }
        return connection;
    }
    
    /**
     * Closes the database connection.
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
                System.out.println("H2 Database connection closed.");
            } catch (SQLException e) {
                System.err.println("Error closing database: " + e.getMessage());
            }
            connection = null;
        }
    }
    
    /**
     * Gets the database file path.
     */
    public String getDbPath() {
        return dbPath;
    }
    
    /**
     * Gets the JDBC URL.
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }
    
    /**
     * Compacts the database to reclaim space.
     */
    public void compact() throws SQLException {
        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute("SHUTDOWN COMPACT");
            connection = null; // Connection is closed after SHUTDOWN
            initialize(); // Reconnect
        }
    }
}
