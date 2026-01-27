package com.logdashboard.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.logdashboard.model.DumpFileTracking;
import com.logdashboard.model.DumpProcessConfig;
import com.logdashboard.store.DumpProcessingStore;
import com.logdashboard.store.InfrastructureStore;
import com.logdashboard.watcher.DumpProcessingWatcher;
import com.logdashboard.watcher.DumpScriptExecutor;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/**
 * REST API servlet for database dump processing management.
 * Provides CRUD operations for dump process configurations and file tracking.
 */
public class DumpProcessServlet extends HttpServlet {
    
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    
    private final DumpProcessingStore dumpStore;
    private final InfrastructureStore infraStore;
    private final DumpProcessingWatcher watcher;
    private final DumpScriptExecutor executor;
    
    public DumpProcessServlet(DumpProcessingStore dumpStore, InfrastructureStore infraStore,
                              DumpProcessingWatcher watcher) {
        this.dumpStore = dumpStore;
        this.infraStore = infraStore;
        this.watcher = watcher;
        this.executor = new DumpScriptExecutor();
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
            if (pathInfo.equals("/configs") || pathInfo.equals("/configs/")) {
                handleGetConfigs(out);
            } else if (pathInfo.matches("/configs/\\d+")) {
                int id = extractId(pathInfo, "/configs/");
                handleGetConfigById(id, out, resp);
            } else if (pathInfo.matches("/configs/\\d+/files")) {
                int id = extractIdFromPath(pathInfo, "/configs/", "/files");
                handleGetFilesForConfig(id, out, resp);
            } else if (pathInfo.equals("/status") || pathInfo.equals("/status/")) {
                handleGetStatus(out);
            } else if (pathInfo.equals("/db-types") || pathInfo.equals("/db-types/")) {
                handleGetDbTypes(out);
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
            if (pathInfo.equals("/configs") || pathInfo.equals("/configs/")) {
                String body = readRequestBody(req);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                handleAddConfig(json, out, resp);
            } else if (pathInfo.matches("/configs/\\d+/run")) {
                int id = extractIdFromPath(pathInfo, "/configs/", "/run");
                handleTriggerProcessing(id, out, resp);
            } else if (pathInfo.equals("/validate") || pathInfo.equals("/validate/")) {
                String body = readRequestBody(req);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                handleValidateConfig(json, out, resp);
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
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) 
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
            if (pathInfo.matches("/configs/\\d+")) {
                int id = extractId(pathInfo, "/configs/");
                String body = readRequestBody(req);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                handleUpdateConfig(id, json, out, resp);
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
            if (pathInfo.matches("/configs/\\d+")) {
                int id = extractId(pathInfo, "/configs/");
                handleDeleteConfig(id, out, resp);
            } else if (pathInfo.matches("/files/\\d+")) {
                int id = extractId(pathInfo, "/files/");
                handleDeleteFile(id, out, resp);
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
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Admin-Username, X-Admin-Password");
    }
    
    // ==================== Config Handlers ====================
    // Note: Authentication is intentionally disabled for dump processing operations.
    // All CRUD operations are allowed without admin credentials per user requirement.
    
    private void handleGetConfigs(PrintWriter out) {
        List<DumpProcessConfig> configs = dumpStore.getAllConfigs();
        
        // Add stats for each config
        List<Map<String, Object>> configsWithStats = new ArrayList<>();
        for (DumpProcessConfig config : configs) {
            Map<String, Object> configMap = configToMap(config);
            DumpProcessingStore.ProcessingStats stats = dumpStore.getStatsForConfig(config.getId());
            configMap.put("stats", statsToMap(stats));
            configsWithStats.add(configMap);
        }
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", configs.size());
        response.put("configs", configsWithStats);
        out.write(GSON.toJson(response));
    }
    
    private void handleGetConfigById(int id, PrintWriter out, HttpServletResponse resp) {
        Optional<DumpProcessConfig> config = dumpStore.getConfigById(id);
        
        if (config.isPresent()) {
            Map<String, Object> configMap = configToMap(config.get());
            DumpProcessingStore.ProcessingStats stats = dumpStore.getStatsForConfig(id);
            configMap.put("stats", statsToMap(stats));
            out.write(GSON.toJson(configMap));
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Config not found: " + id)));
        }
    }
    
    private void handleAddConfig(JsonObject json, PrintWriter out, HttpServletResponse resp) {
        try {
            DumpProcessConfig config = jsonToConfig(json);
            
            // Validate
            String validationError = validateConfigFields(config);
            if (validationError != null) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.write(GSON.toJson(error(validationError)));
                return;
            }
            
            DumpProcessConfig created = dumpStore.addConfig(config);
            resp.setStatus(HttpServletResponse.SC_CREATED);
            out.write(GSON.toJson(configToMap(created)));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to add config: " + e.getMessage())));
        }
    }
    
    private void handleUpdateConfig(int id, JsonObject json, PrintWriter out, HttpServletResponse resp) {
        try {
            Optional<DumpProcessConfig> existing = dumpStore.getConfigById(id);
            
            if (!existing.isPresent()) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Config not found: " + id)));
                return;
            }
            
            DumpProcessConfig config = existing.get();
            updateConfigFromJson(config, json);
            
            // Validate
            String validationError = validateConfigFields(config);
            if (validationError != null) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.write(GSON.toJson(error(validationError)));
                return;
            }
            
            boolean updated = dumpStore.updateConfig(config);
            if (updated) {
                out.write(GSON.toJson(configToMap(config)));
            } else {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                out.write(GSON.toJson(error("Failed to update config")));
            }
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to update config: " + e.getMessage())));
        }
    }
    
    private void handleDeleteConfig(int id, PrintWriter out, HttpServletResponse resp) {
        try {
            boolean deleted = dumpStore.deleteConfig(id);
            
            if (deleted) {
                out.write(GSON.toJson(success("Config deleted successfully")));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Config not found: " + id)));
            }
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to delete config: " + e.getMessage())));
        }
    }
    
    // ==================== File Handlers ====================
    
    private void handleGetFilesForConfig(int configId, PrintWriter out, HttpServletResponse resp) {
        Optional<DumpProcessConfig> config = dumpStore.getConfigById(configId);
        
        if (!config.isPresent()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Config not found: " + configId)));
            return;
        }
        
        List<DumpFileTracking> files = dumpStore.getFilesForConfig(configId);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("configId", configId);
        response.put("serverName", config.get().getServerName());
        response.put("count", files.size());
        response.put("files", files.stream().map(this::fileToMap).toArray());
        out.write(GSON.toJson(response));
    }
    
    private void handleDeleteFile(int fileId, PrintWriter out, HttpServletResponse resp) {
        try {
            boolean deleted = dumpStore.deleteFileTracking(fileId);
            
            if (deleted) {
                out.write(GSON.toJson(success("File tracking record deleted")));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("File tracking record not found: " + fileId)));
            }
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to delete file tracking: " + e.getMessage())));
        }
    }
    
    // ==================== Action Handlers ====================
    
    private void handleTriggerProcessing(int configId, PrintWriter out, HttpServletResponse resp) {
        Optional<DumpProcessConfig> config = dumpStore.getConfigById(configId);
        
        if (!config.isPresent()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Config not found: " + configId)));
            return;
        }
        
        if (watcher != null && watcher.isRunning()) {
            watcher.triggerProcessing(configId);
            out.write(GSON.toJson(success("Processing triggered for " + config.get().getServerName())));
        } else {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            out.write(GSON.toJson(error("Dump processing watcher is not running")));
        }
    }
    
    private void handleValidateConfig(JsonObject json, PrintWriter out, HttpServletResponse resp) {
        DumpProcessConfig config = jsonToConfig(json);
        
        // Validate fields
        String fieldError = validateConfigFields(config);
        if (fieldError != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", false);
            result.put("error", fieldError);
            out.write(GSON.toJson(result));
            return;
        }
        
        // Validate paths using executor
        String pathError = executor.validateConfig(config);
        if (pathError != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", false);
            result.put("error", pathError);
            out.write(GSON.toJson(result));
            return;
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("command", config.buildFullCommand());
        out.write(GSON.toJson(result));
    }
    
    // ==================== Status Handlers ====================
    
    private void handleGetStatus(PrintWriter out) {
        List<DumpProcessConfig> configs = dumpStore.getAllConfigs();
        List<DumpFileTracking> processingFiles = dumpStore.getProcessingFiles();
        
        int totalPending = 0;
        int totalCompleted = 0;
        int totalCompletedWithErrors = 0;
        int totalFailed = 0;
        
        for (DumpProcessConfig config : configs) {
            DumpProcessingStore.ProcessingStats stats = dumpStore.getStatsForConfig(config.getId());
            totalPending += stats.pendingCount;
            totalCompleted += stats.completedCount;
            totalCompletedWithErrors += stats.completedWithErrorsCount;
            totalFailed += stats.failedCount;
        }
        
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("watcherRunning", watcher != null && watcher.isRunning());
        status.put("configCount", configs.size());
        status.put("enabledConfigCount", configs.stream().filter(DumpProcessConfig::isEnabled).count());
        status.put("currentlyProcessing", processingFiles.size());
        status.put("totalPending", totalPending);
        status.put("totalCompleted", totalCompleted);
        status.put("totalCompletedWithErrors", totalCompletedWithErrors);
        status.put("totalFailed", totalFailed);
        
        // Add currently processing files
        List<Map<String, Object>> processingList = new ArrayList<>();
        for (DumpFileTracking file : processingFiles) {
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("fileName", file.getFileName());
            fileInfo.put("configId", file.getConfigId());
            fileInfo.put("startTime", file.getProcessStartTime());
            processingList.add(fileInfo);
        }
        status.put("processingFiles", processingList);
        
        out.write(GSON.toJson(status));
    }
    
    private void handleGetDbTypes(PrintWriter out) {
        List<Map<String, String>> dbTypes = new ArrayList<>();
        
        Map<String, String> sqlite = new LinkedHashMap<>();
        sqlite.put("value", DumpProcessConfig.DB_TYPE_SQLITE);
        sqlite.put("label", "SQLite");
        dbTypes.add(sqlite);
        
        Map<String, String> datacom = new LinkedHashMap<>();
        datacom.put("value", DumpProcessConfig.DB_TYPE_DATACOM);
        datacom.put("label", "Datacom");
        dbTypes.add(datacom);
        
        out.write(GSON.toJson(dbTypes));
    }
    
    // ==================== Utility Methods ====================
    
    private int extractId(String path, String prefix) {
        String idStr = path.substring(prefix.length());
        return Integer.parseInt(idStr);
    }
    
    private int extractIdFromPath(String path, String prefix, String suffix) {
        String idStr = path.substring(prefix.length(), path.indexOf(suffix));
        return Integer.parseInt(idStr);
    }
    
    private String readRequestBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
    
    private String getStringOrDefault(JsonObject json, String key, String defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : defaultValue;
    }
    
    private int getIntOrDefault(JsonObject json, String key, int defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : defaultValue;
    }
    
    private boolean getBooleanOrDefault(JsonObject json, String key, boolean defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : defaultValue;
    }
    
    private DumpProcessConfig jsonToConfig(JsonObject json) {
        DumpProcessConfig config = new DumpProcessConfig();
        config.setServerName(getStringOrDefault(json, "serverName", ""));
        config.setDbFolder(getStringOrDefault(json, "dbFolder", ""));
        config.setDumpFolder(getStringOrDefault(json, "dumpFolder", ""));
        config.setDbType(getStringOrDefault(json, "dbType", ""));
        config.setJavaPath(getStringOrDefault(json, "javaPath", ""));
        config.setThresholdMinutes(getIntOrDefault(json, "thresholdMinutes", 1));
        config.setAdminUser(getStringOrDefault(json, "adminUser", null));
        config.setAdminPassword(getStringOrDefault(json, "adminPassword", null));
        config.setEnabled(getBooleanOrDefault(json, "enabled", true));
        return config;
    }
    
    private void updateConfigFromJson(DumpProcessConfig config, JsonObject json) {
        // Use null-safe getters to handle explicit null values in JSON
        if (json.has("serverName") && !json.get("serverName").isJsonNull()) {
            config.setServerName(json.get("serverName").getAsString());
        }
        if (json.has("dbFolder") && !json.get("dbFolder").isJsonNull()) {
            config.setDbFolder(json.get("dbFolder").getAsString());
        }
        if (json.has("dumpFolder") && !json.get("dumpFolder").isJsonNull()) {
            config.setDumpFolder(json.get("dumpFolder").getAsString());
        }
        if (json.has("dbType") && !json.get("dbType").isJsonNull()) {
            config.setDbType(json.get("dbType").getAsString());
        }
        if (json.has("javaPath") && !json.get("javaPath").isJsonNull()) {
            config.setJavaPath(json.get("javaPath").getAsString());
        }
        if (json.has("thresholdMinutes") && !json.get("thresholdMinutes").isJsonNull()) {
            config.setThresholdMinutes(json.get("thresholdMinutes").getAsInt());
        }
        if (json.has("adminUser")) {
            config.setAdminUser(json.get("adminUser").isJsonNull() ? null : json.get("adminUser").getAsString());
        }
        if (json.has("adminPassword")) {
            config.setAdminPassword(json.get("adminPassword").isJsonNull() ? null : json.get("adminPassword").getAsString());
        }
        if (json.has("enabled") && !json.get("enabled").isJsonNull()) {
            config.setEnabled(json.get("enabled").getAsBoolean());
        }
    }
    
    private String validateConfigFields(DumpProcessConfig config) {
        if (config.getServerName() == null || config.getServerName().trim().isEmpty()) {
            return "Server name is required";
        }
        if (config.getDbFolder() == null || config.getDbFolder().trim().isEmpty()) {
            return "DB folder is required";
        }
        if (config.getDumpFolder() == null || config.getDumpFolder().trim().isEmpty()) {
            return "Dump folder is required";
        }
        if (config.getDbType() == null || !DumpProcessConfig.isValidDbType(config.getDbType())) {
            return "DB type must be SQLITE or DATACOM";
        }
        if (config.getJavaPath() == null || config.getJavaPath().trim().isEmpty()) {
            return "Java path is required";
        }
        if (config.getThresholdMinutes() < 1) {
            return "Threshold must be at least 1 minute";
        }
        return null;
    }
    
    private Map<String, Object> configToMap(DumpProcessConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", config.getId());
        map.put("serverName", config.getServerName());
        map.put("dbFolder", config.getDbFolder());
        map.put("dumpFolder", config.getDumpFolder());
        map.put("dbType", config.getDbType());
        map.put("javaPath", config.getJavaPath());
        map.put("thresholdMinutes", config.getThresholdMinutes());
        map.put("adminUser", config.getAdminUser());
        // Don't expose the actual password, just indicate if one is set
        map.put("hasPassword", config.getAdminPassword() != null && !config.getAdminPassword().isEmpty());
        map.put("enabled", config.isEnabled());
        map.put("lastRunTime", config.getLastRunTime());
        map.put("lastRunStatus", config.getLastRunStatus());
        map.put("lastRunOutput", config.getLastRunOutput());
        map.put("command", config.buildFullCommand());
        return map;
    }
    
    private Map<String, Object> fileToMap(DumpFileTracking file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", file.getId());
        map.put("configId", file.getConfigId());
        map.put("fileName", file.getFileName());
        map.put("filePath", file.getFilePath());
        map.put("fileSize", file.getFileSize());
        map.put("firstSeenTime", file.getFirstSeenTime());
        map.put("lastModifiedTime", file.getLastModifiedTime());
        map.put("status", file.getStatus());
        map.put("ageMinutes", file.getAgeMinutes());
        map.put("processStartTime", file.getProcessStartTime());
        map.put("processEndTime", file.getProcessEndTime());
        map.put("processOutput", file.getProcessOutput());
        map.put("retryCount", file.getRetryCount());
        return map;
    }
    
    private Map<String, Object> statsToMap(DumpProcessingStore.ProcessingStats stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pending", stats.pendingCount);
        map.put("processing", stats.processingCount);
        map.put("completed", stats.completedCount);
        map.put("completedWithErrors", stats.completedWithErrorsCount);
        map.put("failed", stats.failedCount);
        map.put("total", stats.getTotalCount());
        return map;
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
