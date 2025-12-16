package com.logdashboard.web;

import com.logdashboard.analysis.AnalysisService;
import com.logdashboard.store.IssueStore;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.connector.Connector;

import java.io.File;

/**
 * Embedded Tomcat web server for the log dashboard.
 */
public class WebServer {
    
    private final Tomcat tomcat;
    private final int port;
    private final IssueStore issueStore;
    private final AnalysisService analysisService;
    private EventStreamServlet eventStreamServlet;
    
    public WebServer(int port, IssueStore issueStore, AnalysisService analysisService) {
        this.port = port;
        this.issueStore = issueStore;
        this.analysisService = analysisService;
        this.tomcat = new Tomcat();
    }
    
    /**
     * Starts the embedded Tomcat server.
     */
    public void start() throws LifecycleException {
        // Configure Tomcat
        tomcat.setPort(port);
        
        // Set base directory
        String baseDir = System.getProperty("java.io.tmpdir") + "/tomcat-" + port;
        tomcat.setBaseDir(baseDir);
        
        // Configure connector
        Connector connector = tomcat.getConnector();
        connector.setAsyncTimeout(600000); // 10 minutes for SSE
        
        // Create context
        String contextPath = "";
        String docBase = new File(".").getAbsolutePath();
        Context context = tomcat.addContext(contextPath, docBase);
        
        // Add API servlet
        ApiServlet apiServlet = new ApiServlet(issueStore, analysisService);
        Tomcat.addServlet(context, "api", apiServlet);
        context.addServletMappingDecoded("/api/*", "api");
        
        // Add Event Stream servlet (SSE)
        eventStreamServlet = new EventStreamServlet(issueStore);
        Tomcat.addServlet(context, "events", eventStreamServlet).setAsyncSupported(true);
        context.addServletMappingDecoded("/events", "events");
        
        // Add Static file servlet
        StaticFileServlet staticServlet = new StaticFileServlet("static");
        Tomcat.addServlet(context, "static", staticServlet);
        context.addServletMappingDecoded("/*", "static");
        
        // Start the server
        tomcat.start();
        
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║              Log Issue Dashboard Web Server                ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.println("║  Dashboard URL: http://localhost:" + port + "                       ║");
        System.out.println("║  API Endpoint:  http://localhost:" + port + "/api                   ║");
        System.out.println("║  Event Stream:  http://localhost:" + port + "/events                ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
    
    /**
     * Stops the server.
     */
    public void stop() {
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException e) {
            System.err.println("Error stopping Tomcat: " + e.getMessage());
        }
    }
    
    /**
     * Waits for the server to stop.
     */
    public void await() {
        tomcat.getServer().await();
    }
    
    /**
     * Gets the port the server is running on.
     */
    public int getPort() {
        return port;
    }
    
    /**
     * Gets the event stream servlet for broadcasting.
     */
    public EventStreamServlet getEventStreamServlet() {
        return eventStreamServlet;
    }
}
