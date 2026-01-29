package com.logdashboard.model;

/**
 * Model class representing server information (sub-server) for infrastructure management.
 * Each ServerInfo belongs to a ServerGroup (parent server).
 */
public class ServerInfo {
    
    private int id;
    private int groupId;  // Foreign key to ServerGroup
    private String serverName;
    private String dbType;
    private int port;
    private String note;
    
    public ServerInfo() {
    }
    
    public ServerInfo(int id, int groupId, String serverName, String dbType, int port, String note) {
        this.id = id;
        this.groupId = groupId;
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
    
    public int getGroupId() {
        return groupId;
    }
    
    public void setGroupId(int groupId) {
        this.groupId = groupId;
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
                ", groupId=" + groupId +
                ", serverName='" + serverName + '\'' +
                ", dbType='" + dbType + '\'' +
                ", port=" + port +
                ", note='" + note + '\'' +
                '}';
    }
}
