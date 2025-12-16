package com.logdashboard.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.logdashboard.analysis.AnalysisService;
import com.logdashboard.model.LogIssue;
import com.logdashboard.store.IssueStore;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * REST API servlet for the log dashboard.
 * Provides endpoints for issues, statistics, and analysis.
 */
public class ApiServlet extends HttpServlet {
    
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            .create();
    
    private final IssueStore issueStore;
    private final AnalysisService analysisService;
    
    public ApiServlet(IssueStore issueStore, AnalysisService analysisService) {
        this.issueStore = issueStore;
        this.analysisService = analysisService;
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
                case "/issues":
                    handleGetIssues(req, out);
                    break;
                case "/issues/recent":
                    handleGetRecentIssues(req, out);
                    break;
                case "/stats":
                    handleGetStats(out);
                    break;
                case "/stats/dashboard":
                    handleGetDashboardStats(out);
                    break;
                case "/analysis":
                    handleGetAnalysis(out);
                    break;
                case "/analysis/anomalies":
                    handleGetAnomalies(out);
                    break;
                case "/servers":
                    handleGetServers(out);
                    break;
                case "/health":
                    handleHealthCheck(out);
                    break;
                case "/daterange":
                    handleGetDateRange(out);
                    break;
                case "/export":
                    handleExportIssues(req, out, resp);
                    break;
                default:
                    if (pathInfo.startsWith("/issues/")) {
                        String id = pathInfo.substring("/issues/".length());
                        handleGetIssueById(id, out, resp);
                    } else {
                        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        out.write(GSON.toJson(error("Not found: " + pathInfo)));
                    }
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
            if (pathInfo.startsWith("/issues/") && pathInfo.endsWith("/acknowledge")) {
                String id = pathInfo.replace("/issues/", "").replace("/acknowledge", "");
                handleAcknowledgeIssue(id, out, resp);
            } else if ("/issues/clear".equals(pathInfo)) {
                handleClearIssues(out);
            } else if ("/issues/clear-acknowledged".equals(pathInfo)) {
                handleClearAcknowledged(out);
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
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }
    
    private void handleGetIssues(HttpServletRequest req, PrintWriter out) {
        int offset = getIntParam(req, "offset", 0);
        int limit = getIntParam(req, "limit", 100);
        String severity = req.getParameter("severity");
        String server = req.getParameter("server");
        String fromDate = req.getParameter("from");
        String toDate = req.getParameter("to");
        
        // Parse severity
        LogIssue.Severity sev = null;
        if (severity != null && !severity.isEmpty()) {
            try {
                sev = LogIssue.Severity.valueOf(severity.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Ignore invalid severity
            }
        }
        
        // Parse date range
        LocalDateTime from = parseDateTime(fromDate, true);
        LocalDateTime to = parseDateTime(toDate, false);
        
        // Get filtered issues
        List<LogIssue> issues = issueStore.getFilteredIssues(sev, server, from, to, offset, limit);
        long totalFiltered = issueStore.getFilteredIssuesCount(sev, server, from, to);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", issueStore.getCurrentIssuesCount());
        response.put("totalFiltered", totalFiltered);
        response.put("offset", offset);
        response.put("limit", limit);
        response.put("count", issues.size());
        response.put("hasMore", offset + issues.size() < totalFiltered);
        response.put("issues", issues.stream().map(this::issueToMap).toArray());
        
        // Add filter info
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("severity", severity);
        filters.put("server", server);
        filters.put("from", fromDate);
        filters.put("to", toDate);
        response.put("filters", filters);
        
        out.write(GSON.toJson(response));
    }
    
    private LocalDateTime parseDateTime(String dateStr, boolean startOfDay) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        
        try {
            // Try parsing as full datetime (yyyy-MM-ddTHH:mm:ss)
            return LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e1) {
            try {
                // Try parsing as date only (yyyy-MM-dd)
                LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
                return startOfDay ? date.atStartOfDay() : date.atTime(LocalTime.MAX);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
    
    private void handleGetRecentIssues(HttpServletRequest req, PrintWriter out) {
        int minutes = getIntParam(req, "minutes", 5);
        List<LogIssue> issues = issueStore.getRecentIssues(minutes);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("minutes", minutes);
        response.put("count", issues.size());
        response.put("issues", issues.stream().map(this::issueToMap).toArray());
        
        out.write(GSON.toJson(response));
    }
    
    private void handleGetIssueById(String id, PrintWriter out, HttpServletResponse resp) {
        Optional<LogIssue> issue = issueStore.getIssueById(id);
        if (issue.isPresent()) {
            out.write(GSON.toJson(issueToMap(issue.get())));
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Issue not found: " + id)));
        }
    }
    
    private void handleGetStats(PrintWriter out) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalIssues", issueStore.getCurrentIssuesCount());
        stats.put("totalEverReceived", issueStore.getTotalIssuesCount());
        stats.put("severityDistribution", issueStore.getCurrentSeverityDistribution());
        stats.put("serverCounts", issueStore.getServerCounts());
        stats.put("activeServers", issueStore.getActiveServers());
        stats.put("exceptionTypes", issueStore.getExceptionTypeDistribution());
        
        out.write(GSON.toJson(stats));
    }
    
    private void handleGetDashboardStats(PrintWriter out) {
        AnalysisService.DashboardStats stats = analysisService.getDashboardStats();
        out.write(GSON.toJson(stats));
    }
    
    private void handleGetAnalysis(PrintWriter out) {
        AnalysisService.AnalysisReport report = analysisService.getAnalysisReport();
        out.write(GSON.toJson(report));
    }
    
    private void handleGetAnomalies(PrintWriter out) {
        List<AnalysisService.Anomaly> anomalies = analysisService.detectAnomalies();
        out.write(GSON.toJson(anomalies));
    }
    
    private void handleGetServers(PrintWriter out) {
        Set<String> servers = issueStore.getActiveServers();
        Map<String, Long> counts = issueStore.getServerCounts();
        
        List<Map<String, Object>> serverList = new ArrayList<>();
        for (String server : servers) {
            Map<String, Object> serverInfo = new LinkedHashMap<>();
            serverInfo.put("name", server);
            serverInfo.put("issueCount", counts.getOrDefault(server, 0L));
            serverList.add(serverInfo);
        }
        
        out.write(GSON.toJson(serverList));
    }
    
    private void handleHealthCheck(PrintWriter out) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("issueCount", issueStore.getCurrentIssuesCount());
        out.write(GSON.toJson(health));
    }
    
    private void handleGetDateRange(PrintWriter out) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Map<String, Object> dateRange = new LinkedHashMap<>();
        
        issueStore.getEarliestIssueTime().ifPresentOrElse(
                earliest -> dateRange.put("earliest", earliest.format(formatter)),
                () -> dateRange.put("earliest", null)
        );
        
        issueStore.getLatestIssueTime().ifPresentOrElse(
                latest -> dateRange.put("latest", latest.format(formatter)),
                () -> dateRange.put("latest", null)
        );
        
        dateRange.put("totalIssues", issueStore.getCurrentIssuesCount());
        
        out.write(GSON.toJson(dateRange));
    }
    
    private void handleExportIssues(HttpServletRequest req, PrintWriter out, HttpServletResponse resp) {
        String severity = req.getParameter("severity");
        String server = req.getParameter("server");
        String fromDate = req.getParameter("from");
        String toDate = req.getParameter("to");
        String format = req.getParameter("format"); // json or csv
        
        // Parse filters
        LogIssue.Severity sev = null;
        if (severity != null && !severity.isEmpty()) {
            try {
                sev = LogIssue.Severity.valueOf(severity.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Ignore invalid severity
            }
        }
        
        LocalDateTime from = parseDateTime(fromDate, true);
        LocalDateTime to = parseDateTime(toDate, false);
        
        // Get all filtered issues (no pagination for export)
        List<LogIssue> issues = issueStore.getFilteredIssues(sev, server, from, to, 0, Integer.MAX_VALUE);
        
        if ("csv".equalsIgnoreCase(format)) {
            resp.setContentType("text/csv");
            resp.setHeader("Content-Disposition", "attachment; filename=log-issues-export.csv");
            exportAsCsv(issues, out);
        } else {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("exportedAt", LocalDateTime.now().toString());
            response.put("totalIssues", issues.size());
            response.put("issues", issues.stream().map(this::issueToMap).toArray());
            out.write(GSON.toJson(response));
        }
    }
    
    private void exportAsCsv(List<LogIssue> issues, PrintWriter out) {
        // CSV header
        out.println("ID,Server,File,Line,Type,Severity,Message,Detected At,Acknowledged");
        
        // CSV rows
        for (LogIssue issue : issues) {
            out.printf("\"%s\",\"%s\",\"%s\",%d,\"%s\",\"%s\",\"%s\",\"%s\",%s%n",
                    escapeCsv(issue.getId()),
                    escapeCsv(issue.getServerName()),
                    escapeCsv(issue.getFileName()),
                    issue.getLineNumber(),
                    escapeCsv(issue.getIssueType()),
                    issue.getSeverity().name(),
                    escapeCsv(issue.getMessage()),
                    issue.getFormattedTime(),
                    issue.isAcknowledged()
            );
        }
    }
    
    private String escapeCsv(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }
    
    private void handleAcknowledgeIssue(String id, PrintWriter out, HttpServletResponse resp) {
        boolean success = issueStore.acknowledgeIssue(id);
        if (success) {
            out.write(GSON.toJson(success("Issue acknowledged")));
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Issue not found: " + id)));
        }
    }
    
    private void handleClearIssues(PrintWriter out) {
        issueStore.clearAll();
        out.write(GSON.toJson(success("All issues cleared")));
    }
    
    private void handleClearAcknowledged(PrintWriter out) {
        issueStore.clearAcknowledged();
        out.write(GSON.toJson(success("Acknowledged issues cleared")));
    }
    
    private Map<String, Object> issueToMap(LogIssue issue) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", issue.getId());
        map.put("serverName", issue.getServerName());
        map.put("fileName", issue.getFileName());
        map.put("lineNumber", issue.getLineNumber());
        map.put("issueType", issue.getIssueType());
        map.put("message", issue.getMessage());
        map.put("fullStackTrace", issue.getFullStackTrace());
        map.put("detectedAt", issue.getFormattedTime());
        map.put("severity", issue.getSeverity().name());
        map.put("severityColor", issue.getSeverity().getColor());
        map.put("acknowledged", issue.isAcknowledged());
        return map;
    }
    
    private int getIntParam(HttpServletRequest req, String name, int defaultValue) {
        String value = req.getParameter(name);
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
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
