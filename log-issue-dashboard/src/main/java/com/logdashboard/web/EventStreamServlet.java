package com.logdashboard.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.logdashboard.model.LogIssue;
import com.logdashboard.store.IssueStore;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events (SSE) servlet for real-time issue streaming.
 * Clients can subscribe to receive new issues as they occur.
 */
public class EventStreamServlet extends HttpServlet {
    
    private static final Gson GSON = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            .create();
    
    private final IssueStore issueStore;
    private final CopyOnWriteArrayList<AsyncContext> clients;
    
    public EventStreamServlet(IssueStore issueStore) {
        this.issueStore = issueStore;
        this.clients = new CopyOnWriteArrayList<>();
        
        // Register listener for new issues
        issueStore.addListener(this::broadcastIssue);
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // Set up SSE response headers
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        
        // Enable async processing
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(0); // No timeout
        
        // Send initial connection event
        try {
            PrintWriter out = resp.getWriter();
            out.write("event: connected\n");
            out.write("data: {\"status\":\"connected\",\"clientCount\":" + (clients.size() + 1) + "}\n\n");
            out.flush();
            
            // Add to active clients
            clients.add(asyncContext);
            
            // Handle client disconnect
            asyncContext.addListener(new jakarta.servlet.AsyncListener() {
                @Override
                public void onComplete(jakarta.servlet.AsyncEvent event) {
                    clients.remove(asyncContext);
                }
                
                @Override
                public void onTimeout(jakarta.servlet.AsyncEvent event) {
                    clients.remove(asyncContext);
                }
                
                @Override
                public void onError(jakarta.servlet.AsyncEvent event) {
                    clients.remove(asyncContext);
                }
                
                @Override
                public void onStartAsync(jakarta.servlet.AsyncEvent event) {
                    // Not needed
                }
            });
            
        } catch (IOException e) {
            asyncContext.complete();
        }
    }
    
    /**
     * Broadcasts a new issue to all connected SSE clients.
     */
    private void broadcastIssue(LogIssue issue) {
        Map<String, Object> eventData = issueToMap(issue);
        String jsonData = GSON.toJson(eventData);
        
        for (AsyncContext client : clients) {
            try {
                PrintWriter out = client.getResponse().getWriter();
                out.write("event: issue\n");
                out.write("data: " + jsonData + "\n\n");
                out.flush();
                
                if (out.checkError()) {
                    clients.remove(client);
                    try {
                        client.complete();
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                clients.remove(client);
                try {
                    client.complete();
                } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * Broadcasts a stats update to all clients.
     */
    public void broadcastStats(Map<String, Object> stats) {
        String jsonData = GSON.toJson(stats);
        
        for (AsyncContext client : clients) {
            try {
                PrintWriter out = client.getResponse().getWriter();
                out.write("event: stats\n");
                out.write("data: " + jsonData + "\n\n");
                out.flush();
            } catch (Exception e) {
                clients.remove(client);
            }
        }
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
    
    /**
     * Gets the current number of connected clients.
     */
    public int getClientCount() {
        return clients.size();
    }
}
