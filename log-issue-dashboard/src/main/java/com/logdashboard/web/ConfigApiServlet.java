package com.logdashboard.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.logdashboard.config.ConfigLoader;
import com.logdashboard.config.DashboardConfig;
import com.logdashboard.config.ServerPath;
import com.logdashboard.watcher.LogFileWatcher;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * REST API servlet for configuration management.
 * Provides endpoints to add/remove/modify watch directories from the UI.
 * 
 * Innovation features:
 * - Real-time path validation before adding
 * - Directory browser for easy path selection
 * - File pattern preview (shows what files would be watched)
 * - Config backup before changes
 * - Health status of each watched directory
 */
public class ConfigApiServlet extends HttpServlet {
    
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    
    private final ConfigLoader configLoader;
    private final DashboardConfig config;
    private final LogFileWatcher logWatcher;
    
    // Common encoding options for the UI
    private static final List<Map<String, String>> ENCODING_OPTIONS = Arrays.asList(
        createEncodingOption("UTF-8", "UTF-8 (Default)", "Standard UTF-8 encoding"),
        createEncodingOption("ISO8859-1", "ISO-8859-1 (Latin-1)", "Western European encoding"),
        createEncodingOption("IBM-1047", "IBM-1047 (z/OS EBCDIC)", "z/OS Unix EBCDIC encoding"),
        createEncodingOption("IBM-037", "IBM-037 (EBCDIC US)", "US/Canada EBCDIC encoding"),
        createEncodingOption("IBM-500", "IBM-500 (EBCDIC Intl)", "International EBCDIC encoding"),
        createEncodingOption("Cp1252", "Windows-1252", "Windows Western European encoding")
    );
    
    private static Map<String, String> createEncodingOption(String value, String label, String description) {
        Map<String, String> option = new LinkedHashMap<>();
        option.put("value", value);
        option.put("label", label);
        option.put("description", description);
        return option;
    }
    
    public ConfigApiServlet(ConfigLoader configLoader, DashboardConfig config, LogFileWatcher logWatcher) {
        this.configLoader = configLoader;
        this.config = config;
        this.logWatcher = logWatcher;
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            pathInfo = "/";
        }
        
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        
        PrintWriter out = resp.getWriter();
        
        try {
            switch (pathInfo) {
                case "/servers":
                case "/watch-directories":
                    handleGetWatchDirectories(out);
                    break;
                case "/status":
                    handleGetStatus(out);
                    break;
                case "/encodings":
                    handleGetEncodings(out);
                    break;
                case "/browse":
                    handleBrowseDirectory(req, out, resp);
                    break;
                case "/validate":
                    handleValidatePath(req, out);
                    break;
                case "/preview":
                    handlePreviewFiles(req, out);
                    break;
                case "/file-patterns":
                    handleGetFilePatterns(out);
                    break;
                case "/settings":
                    handleGetSettings(out);
                    break;
                case "/diagnostics":
                    handleGetDiagnostics(out);
                    break;
                default:
                    resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    out.write(GSON.toJson(error("Not found: " + pathInfo)));
            }
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Internal error: " + e.getMessage())));
            e.printStackTrace();
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            pathInfo = "/";
        }
        
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        
        PrintWriter out = resp.getWriter();
        
        try {
            switch (pathInfo) {
                case "/servers/add":
                case "/watch-directories/add":
                    handleAddWatchDirectory(req, out, resp);
                    break;
                case "/file-patterns/add":
                    handleAddFilePattern(req, out, resp);
                    break;
                case "/diagnostics/verbose":
                    handleToggleVerboseLogging(out, resp);
                    break;
                case "/diagnostics/rescan":
                    handleRescan(out, resp);
                    break;
                default:
                    resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    out.write(GSON.toJson(error("Not found: " + pathInfo)));
            }
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Internal error: " + e.getMessage())));
            e.printStackTrace();
        }
    }
    
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            pathInfo = "/";
        }
        
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        
        PrintWriter out = resp.getWriter();
        
        try {
            if (pathInfo.startsWith("/servers/") || pathInfo.startsWith("/watch-directories/")) {
                String serverName = pathInfo.replace("/servers/", "").replace("/watch-directories/", "");
                handleRemoveWatchDirectory(serverName, out, resp);
            } else if (pathInfo.startsWith("/file-patterns/")) {
                String pattern = pathInfo.substring("/file-patterns/".length());
                handleRemoveFilePattern(pattern, out, resp);
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Not found: " + pathInfo)));
            }
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Internal error: " + e.getMessage())));
            e.printStackTrace();
        }
    }
    
    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
    
    /**
     * Check if the request is authenticated with admin credentials.
     */
    private boolean isAuthenticated(HttpServletRequest req) {
        if (!config.hasAdminCredentials()) {
            // No admin credentials configured - allow read-only mode
            return false;
        }
        
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }
        
        try {
            String base64Credentials = authHeader.substring("Basic ".length()).trim();
            String credentials = new String(Base64.getDecoder().decode(base64Credentials));
            String[] parts = credentials.split(":", 2);
            
            if (parts.length == 2) {
                String username = parts[0];
                String password = parts[1];
                return config.getAdminUsername().equals(username) 
                    && config.getAdminPassword().equals(password);
            }
        } catch (Exception e) {
            // Invalid auth header
        }
        
        return false;
    }
    
    /**
     * Get all configured watch directories with status.
     */
    private void handleGetWatchDirectories(PrintWriter out) {
        List<Map<String, Object>> directories = new ArrayList<>();
        
        // Add server-based paths
        if (config.getServers() != null) {
            for (ServerPath server : config.getServers()) {
                Map<String, Object> dir = new LinkedHashMap<>();
                dir.put("serverName", server.getServerName());
                dir.put("path", server.getPath());
                dir.put("description", server.getDescription());
                dir.put("encoding", server.getEncoding() != null ? server.getEncoding() : "UTF-8");
                dir.put("useIconv", server.isUseIconv());
                
                // Add status info
                Path path = Paths.get(server.getPath());
                dir.put("exists", Files.exists(path));
                dir.put("isDirectory", Files.isDirectory(path));
                
                // Count files being watched in this directory
                int fileCount = 0;
                if (logWatcher != null) {
                    for (Path trackedFile : logWatcher.getTrackedFiles()) {
                        if (trackedFile.startsWith(path)) {
                            fileCount++;
                        }
                    }
                }
                dir.put("trackedFileCount", fileCount);
                
                directories.add(dir);
            }
        }
        
        // Add legacy watch paths
        if (config.getWatchPaths() != null) {
            for (String pathStr : config.getWatchPaths()) {
                Map<String, Object> dir = new LinkedHashMap<>();
                dir.put("serverName", null);
                dir.put("path", pathStr);
                dir.put("description", "Legacy watch path");
                dir.put("encoding", "UTF-8");
                dir.put("useIconv", false);
                
                Path path = Paths.get(pathStr);
                dir.put("exists", Files.exists(path));
                dir.put("isDirectory", Files.isDirectory(path));
                
                int fileCount = 0;
                if (logWatcher != null) {
                    for (Path trackedFile : logWatcher.getTrackedFiles()) {
                        if (trackedFile.startsWith(path)) {
                            fileCount++;
                        }
                    }
                }
                dir.put("trackedFileCount", fileCount);
                
                directories.add(dir);
            }
        }
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("directories", directories);
        response.put("totalCount", directories.size());
        response.put("requiresAuth", config.hasAdminCredentials());
        
        out.write(GSON.toJson(response));
    }
    
    /**
     * Get overall watcher status.
     */
    private void handleGetStatus(PrintWriter out) {
        Map<String, Object> status = new LinkedHashMap<>();
        
        status.put("totalWatchDirectories", 
            (config.getServers() != null ? config.getServers().size() : 0) +
            (config.getWatchPaths() != null ? config.getWatchPaths().size() : 0));
        
        if (logWatcher != null) {
            status.put("totalTrackedFiles", logWatcher.getTrackedFiles().size());
        } else {
            status.put("totalTrackedFiles", 0);
        }
        
        status.put("pollingInterval", config.getPollingIntervalSeconds());
        status.put("filePatterns", config.getFilePatterns());
        status.put("configPath", configLoader.getConfigFilePath().toString());
        status.put("requiresAuth", config.hasAdminCredentials());
        
        out.write(GSON.toJson(status));
    }
    
    /**
     * Get available encoding options.
     */
    private void handleGetEncodings(PrintWriter out) {
        out.write(GSON.toJson(ENCODING_OPTIONS));
    }
    
    /**
     * Browse directories on the server.
     * For security, only allows browsing certain base paths.
     */
    private void handleBrowseDirectory(HttpServletRequest req, PrintWriter out, HttpServletResponse resp) 
            throws IOException {
        String pathParam = req.getParameter("path");
        
        // Default to user's home or root
        Path browsePath;
        if (pathParam == null || pathParam.isEmpty()) {
            browsePath = Paths.get(System.getProperty("user.home"));
        } else {
            browsePath = Paths.get(pathParam).toAbsolutePath().normalize();
        }
        
        // Security: check if path is reasonable (not traversing into system dirs)
        if (!isPathSafe(browsePath)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            out.write(GSON.toJson(error("Access to this path is not allowed")));
            return;
        }
        
        if (!Files.exists(browsePath)) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Path does not exist: " + pathParam)));
            return;
        }
        
        if (!Files.isDirectory(browsePath)) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Path is not a directory: " + pathParam)));
            return;
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentPath", browsePath.toString());
        result.put("parentPath", browsePath.getParent() != null ? browsePath.getParent().toString() : null);
        
        List<Map<String, Object>> entries = new ArrayList<>();
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(browsePath)) {
            for (Path entry : stream) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", entry.getFileName().toString());
                info.put("path", entry.toString());
                info.put("isDirectory", Files.isDirectory(entry));
                
                if (Files.isDirectory(entry)) {
                    // Count log files in directory
                    try {
                        long logFileCount = countLogFiles(entry);
                        info.put("logFileCount", logFileCount);
                    } catch (IOException e) {
                        info.put("logFileCount", -1);
                    }
                } else {
                    try {
                        info.put("size", Files.size(entry));
                        info.put("isLogFile", isLogFile(entry));
                    } catch (IOException e) {
                        info.put("size", 0);
                    }
                }
                
                entries.add(info);
            }
        }
        
        // Sort: directories first, then by name
        entries.sort((a, b) -> {
            boolean aIsDir = (Boolean) a.get("isDirectory");
            boolean bIsDir = (Boolean) b.get("isDirectory");
            if (aIsDir != bIsDir) {
                return aIsDir ? -1 : 1;
            }
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });
        
        result.put("entries", entries);
        out.write(GSON.toJson(result));
    }
    
    /**
     * Validate a path before adding.
     */
    private void handleValidatePath(HttpServletRequest req, PrintWriter out) {
        String pathParam = req.getParameter("path");
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", pathParam);
        
        if (pathParam == null || pathParam.isEmpty()) {
            result.put("valid", false);
            result.put("error", "Path is required");
            out.write(GSON.toJson(result));
            return;
        }
        
        Path path;
        try {
            path = Paths.get(pathParam).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            result.put("valid", false);
            result.put("error", "Invalid path format: " + e.getMessage());
            out.write(GSON.toJson(result));
            return;
        }
        
        result.put("resolvedPath", path.toString());
        result.put("exists", Files.exists(path));
        result.put("isDirectory", Files.isDirectory(path));
        result.put("isReadable", Files.isReadable(path));
        
        // Check if already configured
        boolean alreadyConfigured = isPathAlreadyConfigured(path.toString());
        result.put("alreadyConfigured", alreadyConfigured);
        
        if (!Files.exists(path)) {
            result.put("valid", false);
            result.put("error", "Path does not exist");
        } else if (!Files.isReadable(path)) {
            result.put("valid", false);
            result.put("error", "Path is not readable");
        } else if (alreadyConfigured) {
            result.put("valid", false);
            result.put("error", "This path is already configured");
        } else {
            result.put("valid", true);
            
            // Count matching files
            if (Files.isDirectory(path)) {
                try {
                    long logFileCount = countLogFiles(path);
                    result.put("matchingFileCount", logFileCount);
                } catch (IOException e) {
                    result.put("matchingFileCount", -1);
                }
            } else if (isLogFile(path)) {
                result.put("matchingFileCount", 1);
            } else {
                result.put("matchingFileCount", 0);
                result.put("warning", "This file does not match any configured file patterns");
            }
        }
        
        out.write(GSON.toJson(result));
    }
    
    /**
     * Preview which files would be watched in a directory.
     */
    private void handlePreviewFiles(HttpServletRequest req, PrintWriter out) {
        String pathParam = req.getParameter("path");
        int limit = 50; // Limit to prevent huge responses
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", pathParam);
        
        if (pathParam == null || pathParam.isEmpty()) {
            result.put("files", Collections.emptyList());
            out.write(GSON.toJson(result));
            return;
        }
        
        Path path = Paths.get(pathParam);
        
        if (!Files.exists(path)) {
            result.put("files", Collections.emptyList());
            result.put("error", "Path does not exist");
            out.write(GSON.toJson(result));
            return;
        }
        
        List<Map<String, Object>> files = new ArrayList<>();
        
        try {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    int count = 0;
                    for (Path file : stream) {
                        if (Files.isRegularFile(file) && isLogFile(file)) {
                            if (count < limit) {
                                Map<String, Object> fileInfo = new LinkedHashMap<>();
                                fileInfo.put("name", file.getFileName().toString());
                                fileInfo.put("size", Files.size(file));
                                fileInfo.put("lastModified", Files.getLastModifiedTime(file).toString());
                                files.add(fileInfo);
                            }
                            count++;
                        }
                    }
                    result.put("totalMatchingFiles", count);
                    result.put("truncated", count > limit);
                }
            } else if (isLogFile(path)) {
                Map<String, Object> fileInfo = new LinkedHashMap<>();
                fileInfo.put("name", path.getFileName().toString());
                fileInfo.put("size", Files.size(path));
                fileInfo.put("lastModified", Files.getLastModifiedTime(path).toString());
                files.add(fileInfo);
                result.put("totalMatchingFiles", 1);
            }
        } catch (IOException e) {
            result.put("error", "Error reading directory: " + e.getMessage());
        }
        
        result.put("files", files);
        result.put("filePatterns", config.getFilePatterns());
        out.write(GSON.toJson(result));
    }
    
    /**
     * Get configured file patterns.
     */
    private void handleGetFilePatterns(PrintWriter out) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patterns", config.getFilePatterns());
        result.put("requiresAuth", config.hasAdminCredentials());
        out.write(GSON.toJson(result));
    }
    
    /**
     * Get general settings.
     */
    private void handleGetSettings(PrintWriter out) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pollingIntervalSeconds", config.getPollingIntervalSeconds());
        result.put("maxIssuesDisplayed", config.getMaxIssuesDisplayed());
        result.put("enableSound", config.isEnableSound());
        result.put("windowTitle", config.getWindowTitle());
        result.put("webServerPort", config.getWebServerPort());
        result.put("storageType", config.getStorageType());
        result.put("databasePath", config.getDatabasePath());
        result.put("requiresAuth", config.hasAdminCredentials());
        out.write(GSON.toJson(result));
    }
    
    /**
     * Get diagnostic information about the log file watcher.
     * Useful for troubleshooting why the watcher might not be working.
     */
    private void handleGetDiagnostics(PrintWriter out) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        if (logWatcher != null) {
            result.put("watcherAvailable", true);
            result.put("verboseLogging", logWatcher.isVerboseLogging());
            result.putAll(logWatcher.getDiagnostics());
        } else {
            result.put("watcherAvailable", false);
            result.put("error", "Log file watcher is not initialized");
        }
        
        // Add config info
        result.put("configPath", configLoader.getConfigFilePath().toString());
        result.put("configuredServerCount", config.getServers() != null ? config.getServers().size() : 0);
        result.put("configuredWatchPathCount", config.getWatchPaths() != null ? config.getWatchPaths().size() : 0);
        
        // Add pattern info for debugging
        result.put("exceptionPatterns", config.getExceptionPatterns());
        result.put("errorPatterns", config.getErrorPatterns());
        result.put("warningPatterns", config.getWarningPatterns());
        result.put("exclusionPatterns", config.getExclusionPatterns());
        
        out.write(GSON.toJson(result));
    }
    
    /**
     * Toggle verbose logging for the log file watcher.
     */
    private void handleToggleVerboseLogging(PrintWriter out, HttpServletResponse resp) {
        if (logWatcher == null) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            out.write(GSON.toJson(error("Log file watcher is not available")));
            return;
        }
        
        boolean newState = !logWatcher.isVerboseLogging();
        logWatcher.setVerboseLogging(newState);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("verboseLogging", newState);
        result.put("message", "Verbose logging " + (newState ? "enabled" : "disabled"));
        
        out.write(GSON.toJson(result));
    }
    
    /**
     * Force a rescan of all watched directories.
     */
    private void handleRescan(PrintWriter out, HttpServletResponse resp) {
        if (logWatcher == null) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            out.write(GSON.toJson(error("Log file watcher is not available")));
            return;
        }
        
        logWatcher.rescan();
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Rescan initiated");
        result.put("trackedFiles", logWatcher.getTrackedFiles().size());
        
        out.write(GSON.toJson(result));
    }
    
    /**
     * Add a new watch directory.
     */
    private void handleAddWatchDirectory(HttpServletRequest req, PrintWriter out, HttpServletResponse resp) 
            throws IOException {
        // Read JSON body
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        
        JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
        
        String serverName = json.has("serverName") && !json.get("serverName").isJsonNull() 
            ? json.get("serverName").getAsString() : null;
        String path = json.has("path") ? json.get("path").getAsString() : null;
        String description = json.has("description") && !json.get("description").isJsonNull() 
            ? json.get("description").getAsString() : null;
        String encoding = json.has("encoding") && !json.get("encoding").isJsonNull() 
            ? json.get("encoding").getAsString() : "UTF-8";
        boolean useIconv = json.has("useIconv") && json.get("useIconv").getAsBoolean();
        
        // Validation
        if (path == null || path.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Path is required")));
            return;
        }
        
        Path watchPath = Paths.get(path).toAbsolutePath().normalize();
        
        if (!Files.exists(watchPath)) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Path does not exist: " + path)));
            return;
        }
        
        if (!Files.isReadable(watchPath)) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Path is not readable: " + path)));
            return;
        }
        
        if (isPathAlreadyConfigured(watchPath.toString())) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("This path is already configured")));
            return;
        }
        
        // Generate server name if not provided
        if (serverName == null || serverName.trim().isEmpty()) {
            serverName = generateServerName(watchPath);
        }
        
        // Check for duplicate server name
        if (isServerNameExists(serverName)) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Server name already exists: " + serverName)));
            return;
        }
        
        // Backup config before changes
        backupConfig();
        
        // Create new server path
        ServerPath newServer = new ServerPath(serverName, watchPath.toString(), description, encoding, useIconv);
        
        // Add to config
        if (config.getServers() == null) {
            config.setServers(new ArrayList<>());
        }
        config.getServers().add(newServer);
        
        // Save config
        try {
            configLoader.saveConfig(config);
        } catch (IOException e) {
            // Rollback the change
            config.getServers().remove(newServer);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to save configuration: " + e.getMessage())));
            return;
        }
        
        // Start watching the new path immediately
        if (logWatcher != null) {
            logWatcher.addServerPath(serverName, watchPath.toString(), encoding, useIconv);
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Watch directory added successfully");
        result.put("server", Map.of(
            "serverName", serverName,
            "path", watchPath.toString(),
            "description", description != null ? description : "",
            "encoding", encoding
        ));
        
        out.write(GSON.toJson(result));
    }
    
    /**
     * Remove a watch directory.
     */
    private void handleRemoveWatchDirectory(String serverName, PrintWriter out, HttpServletResponse resp) 
            throws IOException {
        
        if (serverName == null || serverName.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Server name is required")));
            return;
        }
        
        // URL decode the server name
        serverName = java.net.URLDecoder.decode(serverName, "UTF-8");
        
        // Find and remove the server
        boolean found = false;
        if (config.getServers() != null) {
            for (Iterator<ServerPath> it = config.getServers().iterator(); it.hasNext();) {
                ServerPath server = it.next();
                if (serverName.equals(server.getServerName())) {
                    // Backup config before changes
                    backupConfig();
                    
                    it.remove();
                    found = true;
                    break;
                }
            }
        }
        
        if (!found) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Server not found: " + serverName)));
            return;
        }
        
        // Save config
        try {
            configLoader.saveConfig(config);
        } catch (IOException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to save configuration: " + e.getMessage())));
            return;
        }
        
        // Note: Files are still being watched until next rescan
        // A full restart would be needed to stop watching removed directories
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Watch directory removed. Changes will take effect on next restart.");
        
        out.write(GSON.toJson(result));
    }
    
    /**
     * Add a new file pattern.
     */
    private void handleAddFilePattern(HttpServletRequest req, PrintWriter out, HttpServletResponse resp) 
            throws IOException {
        // Read JSON body
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        
        JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
        String pattern = json.has("pattern") ? json.get("pattern").getAsString() : null;
        
        if (pattern == null || pattern.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Pattern is required")));
            return;
        }
        
        pattern = pattern.trim();
        
        // Check if already exists
        if (config.getFilePatterns().contains(pattern)) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Pattern already exists: " + pattern)));
            return;
        }
        
        // Backup and add
        backupConfig();
        
        List<String> patterns = new ArrayList<>(config.getFilePatterns());
        patterns.add(pattern);
        config.setFilePatterns(patterns);
        
        try {
            configLoader.saveConfig(config);
        } catch (IOException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to save configuration: " + e.getMessage())));
            return;
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "File pattern added. Restart required for full effect.");
        result.put("patterns", config.getFilePatterns());
        
        out.write(GSON.toJson(result));
    }
    
    /**
     * Remove a file pattern.
     */
    private void handleRemoveFilePattern(String pattern, PrintWriter out, HttpServletResponse resp) 
            throws IOException {
        
        pattern = java.net.URLDecoder.decode(pattern, "UTF-8");
        
        if (!config.getFilePatterns().contains(pattern)) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Pattern not found: " + pattern)));
            return;
        }
        
        // Don't allow removing all patterns
        if (config.getFilePatterns().size() <= 1) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Cannot remove the last file pattern")));
            return;
        }
        
        // Backup and remove
        backupConfig();
        
        List<String> patterns = new ArrayList<>(config.getFilePatterns());
        patterns.remove(pattern);
        config.setFilePatterns(patterns);
        
        try {
            configLoader.saveConfig(config);
        } catch (IOException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to save configuration: " + e.getMessage())));
            return;
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "File pattern removed. Restart required for full effect.");
        result.put("patterns", config.getFilePatterns());
        
        out.write(GSON.toJson(result));
    }
    
    // Helper methods
    
    private boolean isPathSafe(Path path) {
        String pathStr = path.toString().toLowerCase();
        // Block access to sensitive system directories
        return !pathStr.startsWith("/proc") 
            && !pathStr.startsWith("/sys")
            && !pathStr.startsWith("/dev")
            && !pathStr.contains("/..")
            && !pathStr.equals("/");
    }
    
    private boolean isPathAlreadyConfigured(String path) {
        Path normalizedPath = Paths.get(path).toAbsolutePath().normalize();
        
        if (config.getServers() != null) {
            for (ServerPath server : config.getServers()) {
                Path serverPath = Paths.get(server.getPath()).toAbsolutePath().normalize();
                if (normalizedPath.equals(serverPath)) {
                    return true;
                }
            }
        }
        
        if (config.getWatchPaths() != null) {
            for (String wp : config.getWatchPaths()) {
                Path watchPath = Paths.get(wp).toAbsolutePath().normalize();
                if (normalizedPath.equals(watchPath)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean isServerNameExists(String serverName) {
        if (config.getServers() != null) {
            for (ServerPath server : config.getServers()) {
                if (serverName.equals(server.getServerName())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private String generateServerName(Path path) {
        String name = path.getFileName().toString().toUpperCase()
            .replaceAll("[^A-Z0-9]", "-")
            .replaceAll("-+", "-");
        
        // Make unique if needed
        String baseName = name;
        int counter = 1;
        while (isServerNameExists(name)) {
            name = baseName + "-" + counter++;
        }
        
        return name;
    }
    
    private long countLogFiles(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(this::isLogFile)
                .count();
        }
    }
    
    private boolean isLogFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        for (String pattern : config.getFilePatterns()) {
            String regex = pattern.toLowerCase()
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
            if (fileName.matches(regex)) {
                return true;
            }
        }
        return false;
    }
    
    private void backupConfig() {
        try {
            Path configPath = configLoader.getConfigFilePath();
            if (Files.exists(configPath)) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path backupPath = configPath.resolveSibling(
                    configPath.getFileName() + ".backup-" + timestamp);
                Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to backup config: " + e.getMessage());
        }
    }
    
    private Map<String, Object> success(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", message);
        return result;
    }
    
    private Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message);
        return result;
    }
}
