package com.logdashboard.store;

import com.logdashboard.config.DashboardConfig;
import com.logdashboard.model.ServerInfo;
import com.logdashboard.model.VmInfo;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Store for managing server and VM infrastructure information.
 * Uses H2 database for persistence.
 */
public class InfrastructureStore {
    
    private final DatabaseManager databaseManager;
    private final DashboardConfig config;
    
    public InfrastructureStore(DatabaseManager databaseManager, DashboardConfig config) {
        this.databaseManager = databaseManager;
        this.config = config;
    }
    
    /**
     * Initializes the infrastructure tables in the database.
     */
    public void initialize() throws SQLException {
        Connection conn = databaseManager.getConnection();
        try (Statement stmt = conn.createStatement()) {
            // Create server_info table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS server_info (" +
                "    id INT AUTO_INCREMENT PRIMARY KEY," +
                "    server_name VARCHAR(255) NOT NULL," +
                "    db_type VARCHAR(100)," +
                "    port INT," +
                "    note TEXT," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")");
            
            // Create vm_info table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS vm_info (" +
                "    id INT AUTO_INCREMENT PRIMARY KEY," +
                "    vm_name VARCHAR(255) NOT NULL," +
                "    login_username VARCHAR(255)," +
                "    password VARCHAR(255)," +
                "    vm_start_credential_portal TEXT," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")");
            
            System.out.println("Infrastructure tables initialized.");
        }
    }
    
    /**
     * Validates admin credentials against the configured values.
     * Returns false if credentials are not configured or don't match.
     */
    public boolean validateAdmin(String username, String password) {
        if (!config.hasAdminCredentials()) {
            System.err.println("WARNING: Admin credentials not configured. " +
                "Please set 'adminUsername' and 'adminPassword' in dashboard-config.json");
            return false;
        }
        return config.getAdminUsername().equals(username) 
            && config.getAdminPassword().equals(password);
    }
    
    /**
     * Returns true if admin credentials are properly configured.
     */
    public boolean isAdminConfigured() {
        return config.hasAdminCredentials();
    }
    
    // ==================== Server Info Operations ====================
    
    /**
     * Gets all server information.
     */
    public List<ServerInfo> getAllServers() {
        List<ServerInfo> servers = new ArrayList<>();
        String sql = "SELECT id, server_name, db_type, port, note FROM server_info ORDER BY id";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ServerInfo server = new ServerInfo(
                    rs.getInt("id"),
                    rs.getString("server_name"),
                    rs.getString("db_type"),
                    rs.getInt("port"),
                    rs.getString("note")
                );
                servers.add(server);
            }
        } catch (SQLException e) {
            System.err.println("Error getting servers: " + e.getMessage());
        }
        
        return servers;
    }
    
    /**
     * Gets a server by ID.
     */
    public Optional<ServerInfo> getServerById(int id) {
        String sql = "SELECT id, server_name, db_type, port, note FROM server_info WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ServerInfo(
                        rs.getInt("id"),
                        rs.getString("server_name"),
                        rs.getString("db_type"),
                        rs.getInt("port"),
                        rs.getString("note")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting server by ID: " + e.getMessage());
        }
        
        return Optional.empty();
    }
    
    /**
     * Adds a new server.
     */
    public ServerInfo addServer(ServerInfo server) throws SQLException {
        String sql = "INSERT INTO server_info (server_name, db_type, port, note) VALUES (?, ?, ?, ?)";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql, 
                Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, server.getServerName());
            stmt.setString(2, server.getDbType());
            stmt.setInt(3, server.getPort());
            stmt.setString(4, server.getNote());
            
            stmt.executeUpdate();
            
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    server.setId(rs.getInt(1));
                }
            }
        }
        
        return server;
    }
    
    /**
     * Updates an existing server.
     */
    public boolean updateServer(ServerInfo server) throws SQLException {
        String sql = "UPDATE server_info SET server_name = ?, db_type = ?, port = ?, note = ?, " +
                     "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, server.getServerName());
            stmt.setString(2, server.getDbType());
            stmt.setInt(3, server.getPort());
            stmt.setString(4, server.getNote());
            stmt.setInt(5, server.getId());
            
            return stmt.executeUpdate() > 0;
        }
    }
    
    /**
     * Deletes a server by ID.
     */
    public boolean deleteServer(int id) throws SQLException {
        String sql = "DELETE FROM server_info WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        }
    }
    
    // ==================== VM Info Operations ====================
    
    /**
     * Gets all VM information.
     */
    public List<VmInfo> getAllVms() {
        List<VmInfo> vms = new ArrayList<>();
        String sql = "SELECT id, vm_name, login_username, password, vm_start_credential_portal FROM vm_info ORDER BY id";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                VmInfo vm = new VmInfo(
                    rs.getInt("id"),
                    rs.getString("vm_name"),
                    rs.getString("login_username"),
                    rs.getString("password"),
                    rs.getString("vm_start_credential_portal")
                );
                vms.add(vm);
            }
        } catch (SQLException e) {
            System.err.println("Error getting VMs: " + e.getMessage());
        }
        
        return vms;
    }
    
    /**
     * Gets a VM by ID.
     */
    public Optional<VmInfo> getVmById(int id) {
        String sql = "SELECT id, vm_name, login_username, password, vm_start_credential_portal FROM vm_info WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new VmInfo(
                        rs.getInt("id"),
                        rs.getString("vm_name"),
                        rs.getString("login_username"),
                        rs.getString("password"),
                        rs.getString("vm_start_credential_portal")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting VM by ID: " + e.getMessage());
        }
        
        return Optional.empty();
    }
    
    /**
     * Adds a new VM.
     */
    public VmInfo addVm(VmInfo vm) throws SQLException {
        String sql = "INSERT INTO vm_info (vm_name, login_username, password, vm_start_credential_portal) VALUES (?, ?, ?, ?)";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql, 
                Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, vm.getVmName());
            stmt.setString(2, vm.getLoginUsername());
            stmt.setString(3, vm.getPassword());
            stmt.setString(4, vm.getVmStartCredentialPortal());
            
            stmt.executeUpdate();
            
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    vm.setId(rs.getInt(1));
                }
            }
        }
        
        return vm;
    }
    
    /**
     * Updates an existing VM.
     */
    public boolean updateVm(VmInfo vm) throws SQLException {
        String sql = "UPDATE vm_info SET vm_name = ?, login_username = ?, password = ?, " +
                     "vm_start_credential_portal = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, vm.getVmName());
            stmt.setString(2, vm.getLoginUsername());
            stmt.setString(3, vm.getPassword());
            stmt.setString(4, vm.getVmStartCredentialPortal());
            stmt.setInt(5, vm.getId());
            
            return stmt.executeUpdate() > 0;
        }
    }
    
    /**
     * Deletes a VM by ID.
     */
    public boolean deleteVm(int id) throws SQLException {
        String sql = "DELETE FROM vm_info WHERE id = ?";
        
        try (PreparedStatement stmt = databaseManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        }
    }
}
