package com.logdashboard.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.logdashboard.model.LogIssue;
import com.logdashboard.store.IssueRepository;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-Sent Events (SSE) servlet for real-time issue streaming.
 * Clients can subscribe to receive new issues as they occur.
 * Memory optimization: limits maximum concurrent SSE connections.
 * Also tracks viewer information (IP addresses) for dashboard display.
 */
public class EventStreamServlet extends HttpServlet {
    
    private static final Gson GSON = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            .create();
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    // Maximum concurrent SSE connections to prevent memory exhaustion
    private static final int MAX_SSE_CLIENTS = 100;
    
    private final IssueRepository issueStore;
    private final CopyOnWriteArrayList<AsyncContext> clients;
    
    // Track viewer information: maps AsyncContext to ViewerInfo
    private final ConcurrentHashMap<AsyncContext, ViewerInfo> viewerInfoMap;
    
    // Per-client locks to prevent concurrent writes to the same PrintWriter
    // This is necessary because PrintWriter is not thread-safe and concurrent
    // writes can corrupt Tomcat's internal character encoder buffers
    private final ConcurrentHashMap<AsyncContext, ReentrantLock> clientLocks;

    private static final long HEARTBEAT_INTERVAL_SECONDS = 30;
    private final ScheduledExecutorService dispatcher;
    
    public EventStreamServlet(IssueRepository issueStore) {
        this.issueStore = issueStore;
        this.clients = new CopyOnWriteArrayList<>();
        this.viewerInfoMap = new ConcurrentHashMap<>();
        this.clientLocks = new ConcurrentHashMap<>();
        this.dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-dispatcher");
            t.setDaemon(true);
            return t;
        });

        // Register listener for new issues. Broadcasting runs on the dispatcher thread so a
        // slow or stalled browser connection never blocks the log file watcher.
        issueStore.addListener(new IssueRepository.IssueListener() {
            @Override
            public void onNewIssue(LogIssue issue) {
                dispatch(() -> broadcastIssue(issue));
            }

            @Override
            public void onOccurrences(String issueId, int occurrenceCount, LocalDateTime lastSeenAt) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", issueId);
                data.put("occurrenceCount", occurrenceCount);
                data.put("lastSeenAt", lastSeenAt.format(LAST_SEEN_FORMATTER));
                String json = GSON.toJson(data);
                dispatch(() -> broadcastEvent("occurrences", json));
            }
        });

        // Periodic heartbeat keeps connections alive through proxies and detects dead
        // clients, which would otherwise hold one of the limited SSE slots indefinitely
        dispatcher.scheduleWithFixedDelay(this::sendHeartbeat,
            HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void dispatch(Runnable task) {
        try {
            dispatcher.execute(task);
        } catch (RejectedExecutionException e) {
            // Dispatcher shut down
        }
    }

    private static final DateTimeFormatter LAST_SEEN_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private void sendHeartbeat() {
        sendToAll(": heartbeat\n\n");
    }

    private void broadcastEvent(String eventName, String jsonData) {
        sendToAll("event: " + eventName + "\ndata: " + jsonData + "\n\n");
    }

    /**
     * Writes a raw SSE payload to every client and removes clients that fail.
     */
    private void sendToAll(String payload) {
        List<AsyncContext> failedClients = new ArrayList<>();
        for (AsyncContext client : clients) {
            ReentrantLock lock = clientLocks.get(client);
            if (lock == null) {
                continue;
            }
            lock.lock();
            try {
                PrintWriter out = client.getResponse().getWriter();
                out.write(payload);
                out.flush();
                if (out.checkError()) {
                    failedClients.add(client);
                }
            } catch (Exception e) {
                failedClients.add(client);
            } finally {
                lock.unlock();
            }
        }
        for (AsyncContext client : failedClients) {
            removeClient(client);
            try {
                client.complete();
            } catch (Exception ignored) {
                // Already completed or in error state
            }
        }
    }
    
    /**
     * Information about a viewer (connected client).
     */
    public static class ViewerInfo {
        private final String viewerId;
        private final String ipAddress;
        private final LocalDateTime connectedAt;
        
        public ViewerInfo(String ipAddress) {
            this.viewerId = UUID.randomUUID().toString().substring(0, 8);
            this.ipAddress = ipAddress;
            this.connectedAt = LocalDateTime.now();
        }
        
        public String getViewerId() {
            return viewerId;
        }
        
        public String getIpAddress() {
            return ipAddress;
        }
        
        public LocalDateTime getConnectedAt() {
            return connectedAt;
        }
        
        public String getConnectedAtFormatted() {
            return connectedAt.format(TIME_FORMATTER);
        }
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        // Check if we've reached the maximum number of SSE clients
        if (clients.size() >= MAX_SSE_CLIENTS) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Maximum SSE connections reached. Please try again later.\"}");
            return;
        }
        
        // Set up SSE response headers
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        
        // Enable async processing
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(0); // No timeout
        
        // Get client IP address and create viewer info
        String clientIp = getClientIpAddress(req);
        ViewerInfo viewerInfo = new ViewerInfo(clientIp);
        
        // Send initial connection event with viewer's own ID
        try {
            PrintWriter out = resp.getWriter();
            out.write("event: connected\n");
            out.write("data: {\"status\":\"connected\",\"viewerId\":\"" + viewerInfo.getViewerId() + "\",\"clientCount\":" + (clients.size() + 1) + ",\"maxClients\":" + MAX_SSE_CLIENTS + "}\n\n");
            out.flush();
            
            // Add to active clients, viewer map, and create lock for this client
            clients.add(asyncContext);
            viewerInfoMap.put(asyncContext, viewerInfo);
            clientLocks.put(asyncContext, new ReentrantLock());
            
            // Broadcast updated viewer list to all clients
            broadcastViewers();
            
            // Handle client disconnect
            asyncContext.addListener(new jakarta.servlet.AsyncListener() {
                @Override
                public void onComplete(jakarta.servlet.AsyncEvent event) {
                    removeClient(asyncContext);
                }
                
                @Override
                public void onTimeout(jakarta.servlet.AsyncEvent event) {
                    removeClient(asyncContext);
                }
                
                @Override
                public void onError(jakarta.servlet.AsyncEvent event) {
                    removeClient(asyncContext);
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
     * Extracts the client IP address, considering proxies.
     */
    private String getClientIpAddress(HttpServletRequest req) {
        // Check for X-Forwarded-For header (when behind proxy/load balancer)
        String xForwardedFor = req.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Take the first IP in the chain (original client)
            return xForwardedFor.split(",")[0].trim();
        }
        
        // Check for X-Real-IP header
        String xRealIp = req.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        // Fall back to direct connection IP
        return req.getRemoteAddr();
    }
    
    /**
     * Removes a client and broadcasts updated viewer list.
     * Uses a flag to prevent recursive broadcasts and handles the case
     * where this is called from onComplete context.
     */
    private void removeClient(AsyncContext asyncContext) {
        // Only broadcast if we actually removed the client (prevents duplicate broadcasts)
        boolean removed = clients.remove(asyncContext);
        viewerInfoMap.remove(asyncContext);
        clientLocks.remove(asyncContext);
        
        if (removed) {
            // Schedule broadcast on a separate thread to avoid issues with
            // calling from async listener context (onComplete/onError/onTimeout)
            // where the connection may be in an invalid state
            // Small delay to ensure async context cleanup is complete
            try {
                dispatcher.schedule(this::broadcastViewers, 50, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Dispatcher shut down
            }
        }
    }
    
    /**
     * Broadcasts a new issue to all connected SSE clients.
     */
    private void broadcastIssue(LogIssue issue) {
        Map<String, Object> eventData = issueToMap(issue);
        String jsonData = GSON.toJson(eventData);
        
        List<AsyncContext> failedClients = new ArrayList<>();
        
        for (AsyncContext client : clients) {
            ReentrantLock lock = clientLocks.get(client);
            if (lock == null) {
                // Client was removed, skip
                continue;
            }
            
            lock.lock();
            try {
                PrintWriter out = client.getResponse().getWriter();
                out.write("event: issue\n");
                out.write("data: " + jsonData + "\n\n");
                out.flush();
                
                if (out.checkError()) {
                    failedClients.add(client);
                }
            } catch (Exception e) {
                failedClients.add(client);
            } finally {
                lock.unlock();
            }
        }
        
        // Clean up failed clients (removes from both clients and viewerInfoMap)
        for (AsyncContext client : failedClients) {
            removeClient(client);
            try {
                client.complete();
            } catch (Exception ignored) {}
        }
    }
    
    /**
     * Broadcasts a stats update to all clients.
     */
    public void broadcastStats(Map<String, Object> stats) {
        String jsonData = GSON.toJson(stats);
        
        List<AsyncContext> failedClients = new ArrayList<>();
        
        for (AsyncContext client : clients) {
            ReentrantLock lock = clientLocks.get(client);
            if (lock == null) {
                // Client was removed, skip
                continue;
            }
            
            lock.lock();
            try {
                PrintWriter out = client.getResponse().getWriter();
                out.write("event: stats\n");
                out.write("data: " + jsonData + "\n\n");
                out.flush();
                
                if (out.checkError()) {
                    failedClients.add(client);
                }
            } catch (Exception e) {
                failedClients.add(client);
            } finally {
                lock.unlock();
            }
        }
        
        // Clean up failed clients (removes from both clients and viewerInfoMap)
        for (AsyncContext client : failedClients) {
            removeClient(client);
            try {
                client.complete();
            } catch (Exception ignored) {
                // Already completed or in error state
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
        map.put("occurrenceCount", issue.getOccurrenceCount());
        map.put("lastSeenAt", issue.getFormattedLastSeen());
        return map;
    }
    
    /**
     * Gets the current number of connected clients.
     */
    public int getClientCount() {
        return clients.size();
    }
    
    /**
     * Gets information about all current viewers.
     */
    public List<Map<String, Object>> getViewers() {
        List<Map<String, Object>> viewers = new ArrayList<>();
        for (ViewerInfo info : viewerInfoMap.values()) {
            Map<String, Object> viewer = new LinkedHashMap<>();
            viewer.put("id", info.getViewerId());
            viewer.put("ip", info.getIpAddress());
            viewer.put("connectedAt", info.getConnectedAtFormatted());
            viewers.add(viewer);
        }
        return viewers;
    }
    
    /**
     * Broadcasts the current viewer list to all connected clients.
     * Handles failed clients by removing them from the client list.
     */
    public void broadcastViewers() {
        Map<String, Object> viewerData = new LinkedHashMap<>();
        viewerData.put("count", clients.size());
        viewerData.put("maxClients", MAX_SSE_CLIENTS);
        viewerData.put("viewers", getViewers());
        
        String jsonData = GSON.toJson(viewerData);
        
        List<AsyncContext> failedClients = new ArrayList<>();
        
        for (AsyncContext client : clients) {
            ReentrantLock lock = clientLocks.get(client);
            if (lock == null) {
                // Client was removed, skip
                continue;
            }
            
            lock.lock();
            try {
                PrintWriter out = client.getResponse().getWriter();
                out.write("event: viewers\n");
                out.write("data: " + jsonData + "\n\n");
                out.flush();
                
                if (out.checkError()) {
                    failedClients.add(client);
                }
            } catch (Exception e) {
                // Client may have disconnected - mark for removal
                failedClients.add(client);
            } finally {
                lock.unlock();
            }
        }
        
        // Clean up failed clients (remove directly to avoid recursive broadcast)
        for (AsyncContext client : failedClients) {
            clients.remove(client);
            viewerInfoMap.remove(client);
            clientLocks.remove(client);
            try {
                client.complete();
            } catch (Exception ignored) {
                // Already completed or in error state
            }
        }
    }
}
