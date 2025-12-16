package com.logdashboard.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves static files from the classpath resources.
 */
public class StaticFileServlet extends HttpServlet {
    
    private static final Map<String, String> MIME_TYPES = new HashMap<>();
    
    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("htm", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("woff", "font/woff");
        MIME_TYPES.put("woff2", "font/woff2");
        MIME_TYPES.put("ttf", "font/ttf");
    }
    
    private final String resourcePath;
    
    public StaticFileServlet(String resourcePath) {
        this.resourcePath = resourcePath.endsWith("/") ? resourcePath : resourcePath + "/";
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        String path = req.getPathInfo();
        if (path == null || path.equals("/")) {
            path = "/index.html";
        }
        
        // Security: prevent directory traversal
        if (path.contains("..")) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        
        String resourceName = resourcePath + path.substring(1);
        URL resource = getClass().getClassLoader().getResource(resourceName);
        
        if (resource == null) {
            // Try index.html for SPA routing
            resource = getClass().getClassLoader().getResource(resourcePath + "index.html");
            if (resource == null) {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.setContentType("text/html");
                resp.getWriter().write("<h1>404 Not Found</h1>");
                return;
            }
        }
        
        // Set content type
        String extension = getExtension(path);
        String contentType = MIME_TYPES.getOrDefault(extension, "application/octet-stream");
        resp.setContentType(contentType);
        resp.setCharacterEncoding("UTF-8");
        
        // Cache control for static assets
        if (!extension.equals("html")) {
            resp.setHeader("Cache-Control", "max-age=3600");
        } else {
            resp.setHeader("Cache-Control", "no-cache");
        }
        
        // Serve the file
        try (InputStream is = resource.openStream();
             OutputStream os = resp.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
        }
    }
    
    private String getExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot > 0 && lastDot < path.length() - 1) {
            return path.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
}
