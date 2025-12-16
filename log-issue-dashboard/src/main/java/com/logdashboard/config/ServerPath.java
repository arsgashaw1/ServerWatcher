package com.logdashboard.config;

/**
 * Represents a server and its associated log path to watch.
 */
public class ServerPath {
    
    private String serverName;
    private String path;
    private String description;
    
    public ServerPath() {
        // Default constructor for JSON deserialization
    }
    
    public ServerPath(String serverName, String path) {
        this.serverName = serverName;
        this.path = path;
    }
    
    public ServerPath(String serverName, String path, String description) {
        this.serverName = serverName;
        this.path = path;
        this.description = description;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return serverName + " -> " + path;
    }
}
