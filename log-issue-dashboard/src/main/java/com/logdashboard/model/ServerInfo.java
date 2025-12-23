package com.logdashboard.model;

/**
 * Model class representing server information for infrastructure management.
 */
public class ServerInfo {
    
    private int id;
    private String serverName;
    private String dbType;
    private int port;
    private String note;
    
    public ServerInfo() {
    }
    
    public ServerInfo(int id, String serverName, String dbType, int port, String note) {
        this.id = id;
        this.serverName = serverName;
        this.dbType = dbType;
        this.port = port;
        this.note = note;
    }
    
    // Getters and Setters
    
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public String getServerName() {
        return serverName;
    }
    
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }
    
    public String getDbType() {
        return dbType;
    }
    
    public void setDbType(String dbType) {
        this.dbType = dbType;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public String getNote() {
        return note;
    }
    
    public void setNote(String note) {
        this.note = note;
    }
    
    @Override
    public String toString() {
        return "ServerInfo{" +
                "id=" + id +
                ", serverName='" + serverName + '\'' +
                ", dbType='" + dbType + '\'' +
                ", port=" + port +
                ", note='" + note + '\'' +
                '}';
    }
}
