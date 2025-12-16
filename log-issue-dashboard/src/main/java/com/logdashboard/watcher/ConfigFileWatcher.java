package com.logdashboard.watcher;

import com.logdashboard.config.ConfigLoader;
import com.logdashboard.config.DashboardConfig;
import com.logdashboard.config.ServerPath;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Watches the configuration file for changes and notifies listeners when
 * new servers are added to the configuration.
 */
public class ConfigFileWatcher {
    
    private final ConfigLoader configLoader;
    private final Consumer<List<ServerPath>> newServersCallback;
    private final Consumer<String> statusCallback;
    private final ScheduledExecutorService scheduler;
    
    private volatile boolean running;
    private Set<String> knownServerKeys;
    private Set<String> knownWatchPaths;
    private long lastModifiedTime;
    
    public ConfigFileWatcher(ConfigLoader configLoader, 
                             DashboardConfig initialConfig,
                             Consumer<List<ServerPath>> newServersCallback,
                             Consumer<String> statusCallback) {
        this.configLoader = configLoader;
        this.newServersCallback = newServersCallback;
        this.statusCallback = statusCallback;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConfigFileWatcher");
            t.setDaemon(true);
            return t;
        });
        this.running = false;
        
        // Initialize known servers and paths from initial config
        this.knownServerKeys = new HashSet<>();
        this.knownWatchPaths = new HashSet<>();
        
        if (initialConfig.getServers() != null) {
            for (ServerPath server : initialConfig.getServers()) {
                knownServerKeys.add(getServerKey(server));
            }
        }
        
        if (initialConfig.getWatchPaths() != null) {
            knownWatchPaths.addAll(initialConfig.getWatchPaths());
        }
        
        // Get initial modification time
        try {
            Path configFile = configLoader.getConfigFilePath();
            if (Files.exists(configFile)) {
                lastModifiedTime = Files.getLastModifiedTime(configFile).toMillis();
            }
        } catch (IOException e) {
            lastModifiedTime = 0;
        }
    }
    
    /**
     * Creates a unique key for a server path.
     */
    private String getServerKey(ServerPath server) {
        return server.getServerName() + "::" + server.getPath();
    }
    
    /**
     * Starts watching the configuration file for changes.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        
        updateStatus("Configuration file watcher started");
        
        // Check every 2 seconds for config file changes
        scheduler.scheduleAtFixedRate(
            this::checkConfigFile,
            2,
            2,
            TimeUnit.SECONDS
        );
    }
    
    /**
     * Stops the configuration file watcher.
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        updateStatus("Configuration file watcher stopped");
    }
    
    /**
     * Checks if the configuration file has been modified.
     */
    private void checkConfigFile() {
        if (!running) {
            return;
        }
        
        try {
            Path configFile = configLoader.getConfigFilePath();
            
            if (!Files.exists(configFile)) {
                return;
            }
            
            long currentModifiedTime = Files.getLastModifiedTime(configFile).toMillis();
            
            if (currentModifiedTime > lastModifiedTime) {
                lastModifiedTime = currentModifiedTime;
                updateStatus("Configuration file changed, reloading...");
                reloadConfig();
            }
        } catch (IOException e) {
            System.err.println("Error checking config file: " + e.getMessage());
        }
    }
    
    /**
     * Reloads the configuration and checks for new servers.
     */
    private void reloadConfig() {
        try {
            DashboardConfig newConfig = configLoader.loadConfig();
            List<ServerPath> newServers = new ArrayList<>();
            
            // Check for new server-based paths
            if (newConfig.getServers() != null) {
                for (ServerPath server : newConfig.getServers()) {
                    String key = getServerKey(server);
                    if (!knownServerKeys.contains(key)) {
                        knownServerKeys.add(key);
                        newServers.add(server);
                        updateStatus("New server detected: " + server.getServerName() + " -> " + server.getPath());
                    }
                }
            }
            
            // Check for new legacy watch paths (create ServerPath with null name)
            if (newConfig.getWatchPaths() != null) {
                for (String path : newConfig.getWatchPaths()) {
                    if (!knownWatchPaths.contains(path)) {
                        knownWatchPaths.add(path);
                        ServerPath legacyPath = new ServerPath(null, path);
                        newServers.add(legacyPath);
                        updateStatus("New watch path detected: " + path);
                    }
                }
            }
            
            // Notify callback if there are new servers
            if (!newServers.isEmpty()) {
                updateStatus("Added " + newServers.size() + " new server(s)/path(s)");
                newServersCallback.accept(newServers);
            } else {
                updateStatus("Configuration reloaded (no new servers)");
            }
            
        } catch (IOException e) {
            updateStatus("Error reloading configuration: " + e.getMessage());
            System.err.println("Error reloading config: " + e.getMessage());
        }
    }
    
    private void updateStatus(String status) {
        System.out.println("[ConfigWatcher] " + status);
        if (statusCallback != null) {
            statusCallback.accept(status);
        }
    }
    
    /**
     * Returns the set of known server keys.
     */
    public Set<String> getKnownServerKeys() {
        return new HashSet<>(knownServerKeys);
    }
    
    /**
     * Returns the set of known watch paths.
     */
    public Set<String> getKnownWatchPaths() {
        return new HashSet<>(knownWatchPaths);
    }
}
