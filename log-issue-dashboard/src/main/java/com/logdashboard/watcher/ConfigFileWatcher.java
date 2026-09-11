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
    private Map<String, ServerPath> knownServers;
    private volatile Consumer<List<ServerPath>> removedServersCallback;
    private volatile Consumer<List<String>> filePatternsCallback;
    private List<String> knownFilePatterns;
    // Removals seen on the previous read; applied only if the next read confirms them
    private Set<String> pendingRemovalKeys = Collections.emptySet();
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
        this.knownServers = new LinkedHashMap<>();
        this.knownWatchPaths = new HashSet<>();
        
        if (initialConfig.getServers() != null) {
            for (ServerPath server : initialConfig.getServers()) {
                knownServers.put(getServerKey(server), server);
            }
        }
        
        if (initialConfig.getWatchPaths() != null) {
            knownWatchPaths.addAll(initialConfig.getWatchPaths());
        }

        knownFilePatterns = initialConfig.getFilePatterns() != null
            ? new ArrayList<>(initialConfig.getFilePatterns()) : Collections.emptyList();
        
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
        
        // Check every 2 seconds for config file changes (fixed delay: never runs back-to-back)
        scheduler.scheduleWithFixedDelay(
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
            } else if (!pendingRemovalKeys.isEmpty()) {
                // Re-read to confirm pending removals
                reloadConfig();
            }
        } catch (Exception e) {
            // Catch everything (e.g. malformed JSON while the file is being edited):
            // an exception escaping a scheduled task would stop config watching for good
            updateStatus("Error checking config file: " + e.getMessage());
        }
    }
    
    /**
     * Reloads the configuration and checks for new servers.
     */
    private void reloadConfig() {
        try {
            DashboardConfig newConfig = configLoader.loadConfig();
            if (newConfig == null) {
                // Empty file, e.g. an editor truncated it before writing the new content
                updateStatus("Configuration file is empty; keeping current configuration");
                return;
            }
            List<ServerPath> newServers = new ArrayList<>();
            
            // Check for new server-based paths
            if (newConfig.getServers() != null) {
                for (ServerPath server : newConfig.getServers()) {
                    String key = getServerKey(server);
                    if (!knownServers.containsKey(key)) {
                        knownServers.put(key, server);
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
            
            // Apply file pattern changes (an empty list is ignored: it would stop watching everything)
            List<String> newPatterns = newConfig.getFilePatterns();
            if (newPatterns != null && !newPatterns.isEmpty() && !newPatterns.equals(knownFilePatterns)) {
                knownFilePatterns = new ArrayList<>(newPatterns);
                updateStatus("File patterns changed: " + newPatterns);
                Consumer<List<String>> patternsCallback = filePatternsCallback;
                if (patternsCallback != null) {
                    patternsCallback.accept(new ArrayList<>(newPatterns));
                }
            }

            // Detect removed servers/paths so they stop being watched without a restart
            List<ServerPath> removedPaths = detectRemovedPaths(newConfig);
            if (!removedPaths.isEmpty()) {
                for (ServerPath removed : removedPaths) {
                    updateStatus("Watch path removed from configuration: " + removed);
                }
                Consumer<List<ServerPath>> callback = removedServersCallback;
                if (callback != null) {
                    callback.accept(removedPaths);
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
    
    /**
     * Sets the callback notified when servers or watch paths are removed from the config file.
     */
    public void setRemovedServersCallback(Consumer<List<ServerPath>> removedServersCallback) {
        this.removedServersCallback = removedServersCallback;
    }

    /**
     * Sets the callback notified when the file patterns in the config file change.
     */
    public void setFilePatternsCallback(Consumer<List<String>> filePatternsCallback) {
        this.filePatternsCallback = filePatternsCallback;
    }

    /**
     * Returns known servers/paths missing from the new configuration. A removal is only
     * reported once two consecutive reads agree, so a partially written file can never
     * make the dashboard stop watching paths.
     */
    private List<ServerPath> detectRemovedPaths(DashboardConfig newConfig) {
        Set<String> currentServerKeys = new HashSet<>();
        if (newConfig.getServers() != null) {
            for (ServerPath server : newConfig.getServers()) {
                currentServerKeys.add(getServerKey(server));
            }
        }
        Set<String> currentWatchPaths = newConfig.getWatchPaths() != null
            ? new HashSet<>(newConfig.getWatchPaths()) : Collections.emptySet();

        Set<String> removalKeys = new LinkedHashSet<>();
        for (String key : knownServers.keySet()) {
            if (!currentServerKeys.contains(key)) {
                removalKeys.add("S:" + key);
            }
        }
        for (String path : knownWatchPaths) {
            if (!currentWatchPaths.contains(path)) {
                removalKeys.add("P:" + path);
            }
        }

        if (removalKeys.isEmpty()) {
            pendingRemovalKeys = Collections.emptySet();
            return Collections.emptyList();
        }
        if (!removalKeys.equals(pendingRemovalKeys)) {
            pendingRemovalKeys = removalKeys;
            return Collections.emptyList();
        }

        pendingRemovalKeys = Collections.emptySet();
        List<ServerPath> removed = new ArrayList<>();
        for (String removalKey : removalKeys) {
            String value = removalKey.substring(2);
            if (removalKey.startsWith("S:")) {
                removed.add(knownServers.remove(value));
            } else {
                knownWatchPaths.remove(value);
                removed.add(new ServerPath(null, value));
            }
        }
        return removed;
    }

    private void updateStatus(String status) {
        if (statusCallback != null) {
            statusCallback.accept(status);
        } else {
            System.out.println("[ConfigWatcher] " + status);
        }
    }
    
    /**
     * Returns the set of known server keys.
     */
    public Set<String> getKnownServerKeys() {
        return new HashSet<>(knownServers.keySet());
    }
    
    /**
     * Returns the set of known watch paths.
     */
    public Set<String> getKnownWatchPaths() {
        return new HashSet<>(knownWatchPaths);
    }
}
