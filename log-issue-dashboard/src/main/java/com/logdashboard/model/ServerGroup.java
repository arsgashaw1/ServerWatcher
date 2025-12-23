package com.logdashboard.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Model class representing a server group (parent server) for infrastructure management.
 * A server group contains multiple sub-servers (ServerInfo).
 */
public class ServerGroup {
    
    private int id;
    private String serverName;
    private String note;
    private List<ServerInfo> subServers;
    
    public ServerGroup() {
        this.subServers = new ArrayList<>();
    }
    
    public ServerGroup(int id, String serverName, String note) {
        this.id = id;
        this.serverName = serverName;
        this.note = note;
        this.subServers = new ArrayList<>();
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
    
    public String getNote() {
        return note;
    }
    
    public void setNote(String note) {
        this.note = note;
    }
    
    public List<ServerInfo> getSubServers() {
        return subServers;
    }
    
    public void setSubServers(List<ServerInfo> subServers) {
        this.subServers = subServers;
    }
    
    public void addSubServer(ServerInfo server) {
        this.subServers.add(server);
    }
    
    @Override
    public String toString() {
        return "ServerGroup{" +
                "id=" + id +
                ", serverName='" + serverName + '\'' +
                ", note='" + note + '\'' +
                ", subServers=" + subServers.size() +
                '}';
    }
}
