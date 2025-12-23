package com.logdashboard.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.logdashboard.model.ServerGroup;
import com.logdashboard.model.ServerInfo;
import com.logdashboard.model.VmInfo;
import com.logdashboard.store.InfrastructureStore;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/**
 * REST API servlet for infrastructure management (servers and VMs).
 * Provides CRUD operations with admin authentication for modifications.
 */
public class InfraServlet extends HttpServlet {
    
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    
    private final InfrastructureStore infrastructureStore;
    
    public InfraServlet(InfrastructureStore infrastructureStore) {
        this.infrastructureStore = infrastructureStore;
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
            // Check if user is authenticated (optional for GET - affects what data is returned)
            boolean isAuthenticated = isAdminAuthenticated(req);
            
            // Server Groups (parent servers)
            if (pathInfo.equals("/server-groups") || pathInfo.equals("/server-groups/")) {
                handleGetServerGroups(out);
            } else if (pathInfo.matches("/server-groups/\\d+/servers/?")) {
                // Get sub-servers for a group: /server-groups/{groupId}/servers
                String groupIdStr = pathInfo.replaceAll("/server-groups/(\\d+)/servers/?", "$1");
                handleGetServersByGroup(groupIdStr, out, resp);
            } else if (pathInfo.matches("/server-groups/\\d+/?")) {
                // Get a specific server group
                String idStr = pathInfo.replaceAll("/server-groups/(\\d+)/?", "$1");
                handleGetServerGroupById(idStr, out, resp);
            }
            // Sub-servers (for direct access)
            else if (pathInfo.equals("/servers") || pathInfo.equals("/servers/")) {
                handleGetServers(out);
            } else if (pathInfo.startsWith("/servers/")) {
                String idStr = pathInfo.substring("/servers/".length()).replaceAll("/$", "");
                handleGetServerById(idStr, out, resp);
            }
            // VMs
            else if (pathInfo.equals("/vms") || pathInfo.equals("/vms/")) {
                handleGetVms(out, isAuthenticated);
            } else if (pathInfo.startsWith("/vms/")) {
                String idStr = pathInfo.substring("/vms/".length());
                handleGetVmById(idStr, out, resp, isAuthenticated);
            } else if (pathInfo.equals("/auth/validate") || pathInfo.equals("/auth/validate/")) {
                handleValidateAuth(req, out, resp);
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
            // Check admin authentication
            if (!checkAdminAuth(req, resp, out)) {
                return;
            }
            
            String body = readRequestBody(req);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            // Server Groups
            if (pathInfo.equals("/server-groups") || pathInfo.equals("/server-groups/")) {
                handleAddServerGroup(json, out, resp);
            } else if (pathInfo.matches("/server-groups/\\d+/servers/?")) {
                // Add sub-server to a group: /server-groups/{groupId}/servers
                String groupIdStr = pathInfo.replaceAll("/server-groups/(\\d+)/servers/?", "$1");
                handleAddServerToGroup(groupIdStr, json, out, resp);
            }
            // Direct sub-server creation (for backwards compatibility)
            else if (pathInfo.equals("/servers") || pathInfo.equals("/servers/")) {
                handleAddServer(json, out, resp);
            } else if (pathInfo.equals("/vms") || pathInfo.equals("/vms/")) {
                handleAddVm(json, out, resp);
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
            // Check admin authentication
            if (!checkAdminAuth(req, resp, out)) {
                return;
            }
            
            String body = readRequestBody(req);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            // Server Groups
            if (pathInfo.matches("/server-groups/\\d+/?")) {
                String idStr = pathInfo.replaceAll("/server-groups/(\\d+)/?", "$1");
                handleUpdateServerGroup(idStr, json, out, resp);
            }
            // Sub-servers
            else if (pathInfo.startsWith("/servers/")) {
                String idStr = pathInfo.substring("/servers/".length()).replaceAll("/$", "");
                handleUpdateServer(idStr, json, out, resp);
            } else if (pathInfo.startsWith("/vms/")) {
                String idStr = pathInfo.substring("/vms/".length());
                handleUpdateVm(idStr, json, out, resp);
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
            // Check admin authentication
            if (!checkAdminAuth(req, resp, out)) {
                return;
            }
            
            // Server Groups
            if (pathInfo.matches("/server-groups/\\d+/?")) {
                String idStr = pathInfo.replaceAll("/server-groups/(\\d+)/?", "$1");
                handleDeleteServerGroup(idStr, out, resp);
            }
            // Sub-servers
            else if (pathInfo.startsWith("/servers/")) {
                String idStr = pathInfo.substring("/servers/".length()).replaceAll("/$", "");
                handleDeleteServer(idStr, out, resp);
            } else if (pathInfo.startsWith("/vms/")) {
                String idStr = pathInfo.substring("/vms/".length());
                handleDeleteVm(idStr, out, resp);
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
    
    // ==================== Authentication ====================
    
    /**
     * Checks if the request has valid admin authentication.
     * Returns false with error response if authentication fails.
     */
    private boolean checkAdminAuth(HttpServletRequest req, HttpServletResponse resp, PrintWriter out) {
        String username = req.getHeader("X-Admin-Username");
        String password = req.getHeader("X-Admin-Password");
        
        if (!infrastructureStore.isAdminConfigured()) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            out.write(GSON.toJson(error("Admin credentials not configured. Please set 'adminUsername' and 'adminPassword' in dashboard-config.json")));
            return false;
        }
        
        if (username == null || password == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            out.write(GSON.toJson(error("Admin credentials required. Please provide X-Admin-Username and X-Admin-Password headers.")));
            return false;
        }
        
        if (!infrastructureStore.validateAdmin(username, password)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            out.write(GSON.toJson(error("Invalid admin credentials.")));
            return false;
        }
        
        return true;
    }
    
    /**
     * Checks if the request has valid admin authentication without sending error response.
     * Used for optional authentication (e.g., to determine what data to show).
     */
    private boolean isAdminAuthenticated(HttpServletRequest req) {
        String username = req.getHeader("X-Admin-Username");
        String password = req.getHeader("X-Admin-Password");
        
        if (username == null || password == null) {
            return false;
        }
        
        return infrastructureStore.validateAdmin(username, password);
    }
    
    /**
     * Handles auth validation request - used by frontend to verify credentials.
     */
    private void handleValidateAuth(HttpServletRequest req, PrintWriter out, HttpServletResponse resp) {
        String username = req.getHeader("X-Admin-Username");
        String password = req.getHeader("X-Admin-Password");
        
        Map<String, Object> response = new LinkedHashMap<>();
        
        if (!infrastructureStore.isAdminConfigured()) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.put("valid", false);
            response.put("configured", false);
            response.put("error", "Admin credentials not configured on server");
        } else if (username == null || password == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.put("valid", false);
            response.put("configured", true);
            response.put("error", "Credentials not provided");
        } else if (infrastructureStore.validateAdmin(username, password)) {
            response.put("valid", true);
            response.put("configured", true);
        } else {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.put("valid", false);
            response.put("configured", true);
            response.put("error", "Invalid credentials");
        }
        
        out.write(GSON.toJson(response));
    }
    
    // ==================== Server Group Handlers ====================
    
    private void handleGetServerGroups(PrintWriter out) {
        List<ServerGroup> groups = infrastructureStore.getAllServerGroups();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", groups.size());
        response.put("serverGroups", groups);
        out.write(GSON.toJson(response));
    }
    
    private void handleGetServerGroupById(String idStr, PrintWriter out, HttpServletResponse resp) {
        try {
            int id = Integer.parseInt(idStr);
            Optional<ServerGroup> group = infrastructureStore.getServerGroupById(id);
            
            if (group.isPresent()) {
                out.write(GSON.toJson(group.get()));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Server group not found: " + id)));
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid server group ID: " + idStr)));
        }
    }
    
    private void handleGetServersByGroup(String groupIdStr, PrintWriter out, HttpServletResponse resp) {
        try {
            int groupId = Integer.parseInt(groupIdStr);
            List<ServerInfo> servers = infrastructureStore.getServersByGroupId(groupId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("groupId", groupId);
            response.put("count", servers.size());
            response.put("servers", servers);
            out.write(GSON.toJson(response));
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid group ID: " + groupIdStr)));
        }
    }
    
    private void handleAddServerGroup(JsonObject json, PrintWriter out, HttpServletResponse resp) {
        try {
            ServerGroup group = new ServerGroup();
            group.setServerName(getStringOrDefault(json, "serverName", ""));
            group.setNote(getStringOrDefault(json, "note", ""));
            
            if (group.getServerName().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.write(GSON.toJson(error("Server name is required")));
                return;
            }
            
            ServerGroup created = infrastructureStore.addServerGroup(group);
            resp.setStatus(HttpServletResponse.SC_CREATED);
            out.write(GSON.toJson(created));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to add server group: " + e.getMessage())));
        }
    }
    
    private void handleAddServerToGroup(String groupIdStr, JsonObject json, PrintWriter out, HttpServletResponse resp) {
        try {
            int groupId = Integer.parseInt(groupIdStr);
            
            // Verify the group exists
            Optional<ServerGroup> group = infrastructureStore.getServerGroupById(groupId);
            if (!group.isPresent()) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Server group not found: " + groupId)));
                return;
            }
            
            ServerInfo server = new ServerInfo();
            server.setGroupId(groupId);
            server.setServerName(getStringOrDefault(json, "serverName", ""));
            server.setDbType(getStringOrDefault(json, "dbType", ""));
            server.setPort(getIntOrDefault(json, "port", 0));
            server.setNote(getStringOrDefault(json, "note", ""));
            
            if (server.getServerName().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.write(GSON.toJson(error("Server name is required")));
                return;
            }
            
            ServerInfo created = infrastructureStore.addServer(server);
            resp.setStatus(HttpServletResponse.SC_CREATED);
            out.write(GSON.toJson(created));
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid group ID: " + groupIdStr)));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to add server: " + e.getMessage())));
        }
    }
    
    private void handleUpdateServerGroup(String idStr, JsonObject json, PrintWriter out, HttpServletResponse resp) {
        try {
            int id = Integer.parseInt(idStr);
            Optional<ServerGroup> existing = infrastructureStore.getServerGroupById(id);
            
            if (!existing.isPresent()) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Server group not found: " + id)));
                return;
            }
            
            ServerGroup group = existing.get();
            if (json.has("serverName")) group.setServerName(json.get("serverName").getAsString());
            if (json.has("note")) group.setNote(json.get("note").getAsString());
            
            boolean updated = infrastructureStore.updateServerGroup(group);
            if (updated) {
                out.write(GSON.toJson(group));
            } else {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                out.write(GSON.toJson(error("Failed to update server group")));
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid server group ID: " + idStr)));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to update server group: " + e.getMessage())));
        }
    }
    
    private void handleDeleteServerGroup(String idStr, PrintWriter out, HttpServletResponse resp) {
        try {
            int id = Integer.parseInt(idStr);
            boolean deleted = infrastructureStore.deleteServerGroup(id);
            
            if (deleted) {
                out.write(GSON.toJson(success("Server group deleted successfully")));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Server group not found: " + id)));
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid server group ID: " + idStr)));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to delete server group: " + e.getMessage())));
        }
    }
    
    // ==================== Sub-Server Handlers ====================
    
    private void handleGetServers(PrintWriter out) {
        List<ServerGroup> groups = infrastructureStore.getAllServerGroups();
        // Flatten all sub-servers
        List<ServerInfo> allServers = new java.util.ArrayList<>();
        for (ServerGroup group : groups) {
            allServers.addAll(group.getSubServers());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", allServers.size());
        response.put("servers", allServers);
        out.write(GSON.toJson(response));
    }
    
    private void handleGetServerById(String idStr, PrintWriter out, HttpServletResponse resp) {
        try {
            int id = Integer.parseInt(idStr);
            Optional<ServerInfo> server = infrastructureStore.getServerById(id);
            
            if (server.isPresent()) {
                out.write(GSON.toJson(server.get()));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Server not found: " + id)));
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid server ID: " + idStr)));
        }
    }
    
    private void handleAddServer(JsonObject json, PrintWriter out, HttpServletResponse resp) {
        try {
            int groupId = getIntOrDefault(json, "groupId", 0);
            
            if (groupId == 0) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.write(GSON.toJson(error("Group ID is required. Use POST /infra/server-groups/{groupId}/servers instead.")));
                return;
            }
            
            // Verify the group exists
            Optional<ServerGroup> group = infrastructureStore.getServerGroupById(groupId);
            if (!group.isPresent()) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Server group not found: " + groupId)));
                return;
            }
            
            ServerInfo server = new ServerInfo();
            server.setGroupId(groupId);
            server.setServerName(getStringOrDefault(json, "serverName", ""));
            server.setDbType(getStringOrDefault(json, "dbType", ""));
            server.setPort(getIntOrDefault(json, "port", 0));
            server.setNote(getStringOrDefault(json, "note", ""));
            
            if (server.getServerName().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.write(GSON.toJson(error("Server name is required")));
                return;
            }
            
            ServerInfo created = infrastructureStore.addServer(server);
            resp.setStatus(HttpServletResponse.SC_CREATED);
            out.write(GSON.toJson(created));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to add server: " + e.getMessage())));
        }
    }
    
    private void handleUpdateServer(String idStr, JsonObject json, PrintWriter out, HttpServletResponse resp) {
        try {
            int id = Integer.parseInt(idStr);
            Optional<ServerInfo> existing = infrastructureStore.getServerById(id);
            
            if (!existing.isPresent()) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Server not found: " + id)));
                return;
            }
            
            ServerInfo server = existing.get();
            if (json.has("groupId")) {
                int newGroupId = json.get("groupId").getAsInt();
                // Verify the new group exists
                Optional<ServerGroup> group = infrastructureStore.getServerGroupById(newGroupId);
                if (!group.isPresent()) {
                    resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    out.write(GSON.toJson(error("Server group not found: " + newGroupId)));
                    return;
                }
                server.setGroupId(newGroupId);
            }
            if (json.has("serverName")) server.setServerName(json.get("serverName").getAsString());
            if (json.has("dbType")) server.setDbType(json.get("dbType").getAsString());
            if (json.has("port")) server.setPort(json.get("port").getAsInt());
            if (json.has("note")) server.setNote(json.get("note").getAsString());
            
            boolean updated = infrastructureStore.updateServer(server);
            if (updated) {
                out.write(GSON.toJson(server));
            } else {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                out.write(GSON.toJson(error("Failed to update server")));
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid server ID: " + idStr)));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to update server: " + e.getMessage())));
        }
    }
    
    private void handleDeleteServer(String idStr, PrintWriter out, HttpServletResponse resp) {
        try {
            int id = Integer.parseInt(idStr);
            boolean deleted = infrastructureStore.deleteServer(id);
            
            if (deleted) {
                out.write(GSON.toJson(success("Server deleted successfully")));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("Server not found: " + id)));
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid server ID: " + idStr)));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to delete server: " + e.getMessage())));
        }
    }
    
    // ==================== VM Handlers ====================
    
    private void handleGetVms(PrintWriter out, boolean isAuthenticated) {
        List<VmInfo> vms = infrastructureStore.getAllVms();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", vms.size());
        // Mask sensitive data for unauthenticated users
        response.put("vms", vms.stream().map(vm -> vmToMap(vm, isAuthenticated)).toArray());
        out.write(GSON.toJson(response));
    }
    
    private void handleGetVmById(String idStr, PrintWriter out, HttpServletResponse resp, boolean isAuthenticated) {
        try {
            int id = Integer.parseInt(idStr);
            Optional<VmInfo> vm = infrastructureStore.getVmById(id);
            
            if (vm.isPresent()) {
                // Mask sensitive data for unauthenticated users
                out.write(GSON.toJson(vmToMap(vm.get(), isAuthenticated)));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("VM not found: " + id)));
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid VM ID: " + idStr)));
        }
    }
    
    /**
     * Converts VmInfo to a Map, masking sensitive data for unauthenticated users.
     */
    private Map<String, Object> vmToMap(VmInfo vm, boolean includeSensitiveData) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", vm.getId());
        map.put("vmName", vm.getVmName());
        map.put("loginUsername", vm.getLoginUsername());
        
        // Mask password for unauthenticated users
        if (includeSensitiveData) {
            map.put("password", vm.getPassword());
        } else {
            // Show masked password if it exists
            String password = vm.getPassword();
            if (password != null && !password.isEmpty()) {
                map.put("password", "********");
                map.put("hasPassword", true);
            } else {
                map.put("password", null);
                map.put("hasPassword", false);
            }
        }
        
        map.put("vmStartCredentialPortal", vm.getVmStartCredentialPortal());
        return map;
    }
    
    private void handleAddVm(JsonObject json, PrintWriter out, HttpServletResponse resp) {
        try {
            VmInfo vm = new VmInfo();
            vm.setVmName(getStringOrDefault(json, "vmName", ""));
            vm.setLoginUsername(getStringOrDefault(json, "loginUsername", ""));
            vm.setPassword(getStringOrDefault(json, "password", ""));
            vm.setVmStartCredentialPortal(getStringOrDefault(json, "vmStartCredentialPortal", ""));
            
            if (vm.getVmName().isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.write(GSON.toJson(error("VM name is required")));
                return;
            }
            
            VmInfo created = infrastructureStore.addVm(vm);
            resp.setStatus(HttpServletResponse.SC_CREATED);
            out.write(GSON.toJson(created));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to add VM: " + e.getMessage())));
        }
    }
    
    private void handleUpdateVm(String idStr, JsonObject json, PrintWriter out, HttpServletResponse resp) {
        try {
            int id = Integer.parseInt(idStr);
            Optional<VmInfo> existing = infrastructureStore.getVmById(id);
            
            if (!existing.isPresent()) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("VM not found: " + id)));
                return;
            }
            
            VmInfo vm = existing.get();
            if (json.has("vmName")) vm.setVmName(json.get("vmName").getAsString());
            if (json.has("loginUsername")) vm.setLoginUsername(json.get("loginUsername").getAsString());
            if (json.has("password")) vm.setPassword(json.get("password").getAsString());
            if (json.has("vmStartCredentialPortal")) vm.setVmStartCredentialPortal(json.get("vmStartCredentialPortal").getAsString());
            
            boolean updated = infrastructureStore.updateVm(vm);
            if (updated) {
                out.write(GSON.toJson(vm));
            } else {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                out.write(GSON.toJson(error("Failed to update VM")));
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid VM ID: " + idStr)));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to update VM: " + e.getMessage())));
        }
    }
    
    private void handleDeleteVm(String idStr, PrintWriter out, HttpServletResponse resp) {
        try {
            int id = Integer.parseInt(idStr);
            boolean deleted = infrastructureStore.deleteVm(id);
            
            if (deleted) {
                out.write(GSON.toJson(success("VM deleted successfully")));
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.write(GSON.toJson(error("VM not found: " + id)));
            }
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid VM ID: " + idStr)));
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to delete VM: " + e.getMessage())));
        }
    }
    
    // ==================== Utility Methods ====================
    
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
