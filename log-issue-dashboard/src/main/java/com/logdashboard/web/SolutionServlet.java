package com.logdashboard.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.logdashboard.model.IssueSolution;
import com.logdashboard.model.LogIssue;
import com.logdashboard.service.SolutionMatchingService;
import com.logdashboard.store.IssueRepository;
import com.logdashboard.store.SolutionStore;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API servlet for managing issue solutions.
 * Provides endpoints for CRUD operations on solutions and suggestions.
 */
public class SolutionServlet extends HttpServlet {
    
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            .create();
    
    private final SolutionStore solutionStore;
    private final IssueRepository issueStore;
    private final SolutionMatchingService matchingService;
    
    public SolutionServlet(SolutionStore solutionStore, IssueRepository issueStore, 
                          SolutionMatchingService matchingService) {
        this.solutionStore = solutionStore;
        this.issueStore = issueStore;
        this.matchingService = matchingService;
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
            if ("/".equals(pathInfo) || pathInfo.isEmpty()) {
                handleListSolutions(req, out);
            } else if ("/search".equals(pathInfo)) {
                handleSearchSolutions(req, out);
            } else if ("/stats".equals(pathInfo)) {
                handleGetStats(out);
            } else if (pathInfo.matches("/[a-f0-9-]+")) {
                String id = pathInfo.substring(1);
                handleGetSolution(id, out, resp);
            } else if (pathInfo.matches("/[a-f0-9-]+/stats")) {
                String id = pathInfo.substring(1, pathInfo.indexOf("/stats"));
                handleGetSolutionStats(id, out, resp);
            } else if (pathInfo.startsWith("/issue/")) {
                String issueId = pathInfo.substring("/issue/".length());
                handleGetSuggestionsForIssue(issueId, out, resp);
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
            if ("/".equals(pathInfo) || pathInfo.isEmpty()) {
                handleCreateSolution(req, out, resp);
            } else if (pathInfo.matches("/[a-f0-9-]+/upvote")) {
                String id = pathInfo.substring(1, pathInfo.indexOf("/upvote"));
                handleVoteSolution(id, true, out, resp);
            } else if (pathInfo.matches("/[a-f0-9-]+/downvote")) {
                String id = pathInfo.substring(1, pathInfo.indexOf("/downvote"));
                handleVoteSolution(id, false, out, resp);
            } else if (pathInfo.matches("/[a-f0-9-]+/archive")) {
                String id = pathInfo.substring(1, pathInfo.indexOf("/archive"));
                handleArchiveSolution(id, out, resp);
            } else if (pathInfo.startsWith("/match/") && pathInfo.endsWith("/feedback")) {
                String matchId = pathInfo.substring("/match/".length(), pathInfo.indexOf("/feedback"));
                handleMatchFeedback(matchId, req, out, resp);
            } else if (pathInfo.startsWith("/from-issue/")) {
                String issueId = pathInfo.substring("/from-issue/".length());
                handleCreateFromIssue(issueId, req, out, resp);
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
        if (pathInfo == null || pathInfo.equals("/")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write(GSON.toJson(error("Solution ID required")));
            return;
        }
        
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        
        String id = pathInfo.substring(1);
        handleUpdateSolution(id, req, resp.getWriter(), resp);
    }
    
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write(GSON.toJson(error("Solution ID required")));
            return;
        }
        
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        
        String id = pathInfo.substring(1);
        handleDeleteSolution(id, resp.getWriter(), resp);
    }
    
    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }
    
    // Handler methods
    
    private void handleListSolutions(HttpServletRequest req, PrintWriter out) {
        int offset = getIntParam(req, "offset", 0);
        int limit = getIntParam(req, "limit", 50);
        boolean includeArchived = "true".equals(req.getParameter("includeArchived"));
        
        List<IssueSolution> solutions = solutionStore.getSolutions(offset, limit, includeArchived);
        int total = solutionStore.getTotalSolutionCount(includeArchived);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", total);
        response.put("offset", offset);
        response.put("limit", limit);
        response.put("count", solutions.size());
        response.put("solutions", solutions.stream().map(this::solutionToMap).collect(Collectors.toList()));
        
        out.write(GSON.toJson(response));
    }
    
    private void handleSearchSolutions(HttpServletRequest req, PrintWriter out) {
        String keyword = req.getParameter("q");
        
        if (keyword == null || keyword.trim().isEmpty()) {
            out.write(GSON.toJson(Collections.singletonMap("solutions", Collections.emptyList())));
            return;
        }
        
        List<IssueSolution> solutions = solutionStore.searchSolutions(keyword);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", keyword);
        response.put("count", solutions.size());
        response.put("solutions", solutions.stream().map(this::solutionToMap).collect(Collectors.toList()));
        
        out.write(GSON.toJson(response));
    }
    
    private void handleGetStats(PrintWriter out) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalActive", solutionStore.getActiveSolutionCount());
        stats.put("totalAll", solutionStore.getTotalSolutionCount(true));
        
        // Get top rated solutions
        List<IssueSolution> topSolutions = solutionStore.getSolutions(0, 5, false);
        stats.put("topSolutions", topSolutions.stream()
            .map(s -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", s.getId());
                map.put("title", s.getSolutionTitle());
                map.put("pattern", s.getIssuePattern());
                map.put("upvotes", s.getUpvotes());
                map.put("usageCount", s.getUsageCount());
                return map;
            })
            .collect(Collectors.toList()));
        
        out.write(GSON.toJson(stats));
    }
    
    private void handleGetSolution(String id, PrintWriter out, HttpServletResponse resp) {
        Optional<IssueSolution> solution = solutionStore.getSolutionById(id);
        
        if (solution.isPresent()) {
            out.write(GSON.toJson(solutionToMap(solution.get())));
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Solution not found: " + id)));
        }
    }
    
    private void handleGetSolutionStats(String id, PrintWriter out, HttpServletResponse resp) {
        Optional<IssueSolution> solution = solutionStore.getSolutionById(id);
        
        if (solution.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Solution not found: " + id)));
            return;
        }
        
        SolutionStore.SolutionStats stats = solutionStore.getSolutionStats(id);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("solutionId", id);
        response.put("totalMatches", stats.totalMatches);
        response.put("helpfulCount", stats.helpfulCount);
        response.put("notHelpfulCount", stats.notHelpfulCount);
        response.put("noFeedbackCount", stats.noFeedbackCount);
        response.put("helpfulRate", stats.getHelpfulRate());
        
        out.write(GSON.toJson(response));
    }
    
    private void handleGetSuggestionsForIssue(String issueId, PrintWriter out, HttpServletResponse resp) {
        Optional<LogIssue> issue = issueStore.getIssueById(issueId);
        
        if (issue.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Issue not found: " + issueId)));
            return;
        }
        
        List<SolutionMatchingService.SuggestionResult> suggestions = 
            matchingService.findSuggestionsForIssue(issue.get());
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("issueId", issueId);
        response.put("issueType", issue.get().getIssueType());
        response.put("count", suggestions.size());
        response.put("suggestions", suggestions.stream()
            .map(SolutionMatchingService.SuggestionResult::toMap)
            .collect(Collectors.toList()));
        
        out.write(GSON.toJson(response));
    }
    
    private void handleCreateSolution(HttpServletRequest req, PrintWriter out, HttpServletResponse resp) 
            throws IOException {
        JsonObject json = parseRequestBody(req);
        
        if (json == null) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid JSON body")));
            return;
        }
        
        // Validate required fields
        if (!json.has("issuePattern") || !json.has("title") || !json.has("description")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Required fields: issuePattern, title, description")));
            return;
        }
        
        String issuePattern = json.get("issuePattern").getAsString();
        String title = json.get("title").getAsString();
        String description = json.get("description").getAsString();
        
        IssueSolution solution = new IssueSolution(issuePattern, title, description);
        
        // Set optional fields
        if (json.has("messagePattern")) {
            solution.setMessagePattern(json.get("messagePattern").getAsString());
        }
        if (json.has("stackPattern")) {
            solution.setStackPattern(json.get("stackPattern").getAsString());
        }
        
        // Set creator info from request
        String createdBy = req.getRemoteAddr();
        if (json.has("createdBy")) {
            createdBy = json.get("createdBy").getAsString();
        }
        solution.setCreatedBy(createdBy);
        
        IssueSolution created = solutionStore.addSolution(solution);
        
        if (created != null) {
            resp.setStatus(HttpServletResponse.SC_CREATED);
            out.write(GSON.toJson(solutionToMap(created)));
        } else {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to create solution")));
        }
    }
    
    private void handleCreateFromIssue(String issueId, HttpServletRequest req, PrintWriter out, 
                                       HttpServletResponse resp) throws IOException {
        Optional<LogIssue> issue = issueStore.getIssueById(issueId);
        
        if (issue.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Issue not found: " + issueId)));
            return;
        }
        
        JsonObject json = parseRequestBody(req);
        
        if (json == null || !json.has("title") || !json.has("description")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Required fields: title, description")));
            return;
        }
        
        String title = json.get("title").getAsString();
        String description = json.get("description").getAsString();
        
        // Use issue type as pattern, or custom pattern if provided
        String issuePattern = issue.get().getIssueType();
        if (json.has("issuePattern")) {
            issuePattern = json.get("issuePattern").getAsString();
        }
        
        IssueSolution solution = new IssueSolution(issuePattern, title, description);
        
        // Set optional patterns
        if (json.has("messagePattern")) {
            solution.setMessagePattern(json.get("messagePattern").getAsString());
        }
        if (json.has("stackPattern")) {
            solution.setStackPattern(json.get("stackPattern").getAsString());
        }
        
        // Set creator info
        String createdBy = req.getRemoteAddr();
        if (json.has("createdBy")) {
            createdBy = json.get("createdBy").getAsString();
        }
        solution.setCreatedBy(createdBy);
        
        IssueSolution created = solutionStore.addSolution(solution);
        
        if (created != null) {
            resp.setStatus(HttpServletResponse.SC_CREATED);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Solution created from issue");
            response.put("solution", solutionToMap(created));
            out.write(GSON.toJson(response));
        } else {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Failed to create solution")));
        }
    }
    
    private void handleUpdateSolution(String id, HttpServletRequest req, PrintWriter out, 
                                      HttpServletResponse resp) throws IOException {
        System.out.println("Updating solution with ID: " + id);
        
        Optional<IssueSolution> existing = solutionStore.getSolutionById(id);
        
        if (existing.isEmpty()) {
            System.out.println("Solution not found: " + id);
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Solution not found: " + id)));
            return;
        }
        
        JsonObject json = parseRequestBody(req);
        
        if (json == null) {
            System.out.println("Invalid JSON body for solution update");
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Invalid JSON body")));
            return;
        }
        
        System.out.println("Update data: " + json.toString());
        
        IssueSolution solution = existing.get();
        
        // Update fields if provided
        if (json.has("issuePattern")) {
            solution.setIssuePattern(json.get("issuePattern").getAsString());
        }
        if (json.has("messagePattern")) {
            String msgPattern = json.get("messagePattern").isJsonNull() ? null : json.get("messagePattern").getAsString();
            solution.setMessagePattern(msgPattern);
        }
        if (json.has("stackPattern")) {
            String stackPattern = json.get("stackPattern").isJsonNull() ? null : json.get("stackPattern").getAsString();
            solution.setStackPattern(stackPattern);
        }
        if (json.has("title")) {
            solution.setSolutionTitle(json.get("title").getAsString());
        }
        if (json.has("description")) {
            solution.setSolutionDescription(json.get("description").getAsString());
        }
        if (json.has("status")) {
            try {
                solution.setStatus(IssueSolution.Status.valueOf(json.get("status").getAsString()));
            } catch (IllegalArgumentException e) {
                // Ignore invalid status
            }
        }
        
        try {
            boolean updated = solutionStore.updateSolution(solution);
            
            if (updated) {
                System.out.println("Solution updated successfully: " + id);
                out.write(GSON.toJson(solutionToMap(solution)));
            } else {
                System.out.println("Failed to update solution in database: " + id);
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                out.write(GSON.toJson(error("Failed to update solution in database")));
            }
        } catch (Exception e) {
            System.err.println("Error updating solution: " + e.getMessage());
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(GSON.toJson(error("Error updating solution: " + e.getMessage())));
        }
    }
    
    private void handleVoteSolution(String id, boolean upvote, PrintWriter out, HttpServletResponse resp) {
        boolean success = upvote ? solutionStore.upvoteSolution(id) : solutionStore.downvoteSolution(id);
        
        if (success) {
            Optional<IssueSolution> solution = solutionStore.getSolutionById(id);
            if (solution.isPresent()) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("upvotes", solution.get().getUpvotes());
                response.put("downvotes", solution.get().getDownvotes());
                response.put("rating", solution.get().getRating());
                out.write(GSON.toJson(response));
            } else {
                out.write(GSON.toJson(success("Vote recorded")));
            }
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Solution not found: " + id)));
        }
    }
    
    private void handleArchiveSolution(String id, PrintWriter out, HttpServletResponse resp) {
        boolean success = solutionStore.archiveSolution(id);
        
        if (success) {
            out.write(GSON.toJson(success("Solution archived")));
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Solution not found: " + id)));
        }
    }
    
    private void handleDeleteSolution(String id, PrintWriter out, HttpServletResponse resp) {
        boolean success = solutionStore.deleteSolution(id);
        
        if (success) {
            out.write(GSON.toJson(success("Solution deleted")));
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Solution not found: " + id)));
        }
    }
    
    private void handleMatchFeedback(String matchId, HttpServletRequest req, PrintWriter out, 
                                     HttpServletResponse resp) throws IOException {
        JsonObject json = parseRequestBody(req);
        
        if (json == null || !json.has("helpful")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write(GSON.toJson(error("Required field: helpful (boolean)")));
            return;
        }
        
        boolean helpful = json.get("helpful").getAsBoolean();
        boolean success = solutionStore.recordMatchFeedback(matchId, helpful);
        
        if (success) {
            out.write(GSON.toJson(success("Feedback recorded")));
        } else {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(GSON.toJson(error("Match not found: " + matchId)));
        }
    }
    
    // Utility methods
    
    private Map<String, Object> solutionToMap(IssueSolution solution) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", solution.getId());
        map.put("issuePattern", solution.getIssuePattern());
        map.put("messagePattern", solution.getMessagePattern());
        map.put("stackPattern", solution.getStackPattern());
        map.put("title", solution.getSolutionTitle());
        map.put("description", solution.getSolutionDescription());
        map.put("createdBy", solution.getCreatedBy());
        map.put("createdAt", solution.getFormattedCreatedAt());
        map.put("updatedAt", solution.getFormattedUpdatedAt());
        map.put("upvotes", solution.getUpvotes());
        map.put("downvotes", solution.getDownvotes());
        map.put("rating", solution.getRating());
        map.put("netScore", solution.getNetScore());
        map.put("usageCount", solution.getUsageCount());
        map.put("status", solution.getStatus().name());
        return map;
    }
    
    private JsonObject parseRequestBody(HttpServletRequest req) throws IOException {
        try (BufferedReader reader = req.getReader()) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return JsonParser.parseString(body.toString()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
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
