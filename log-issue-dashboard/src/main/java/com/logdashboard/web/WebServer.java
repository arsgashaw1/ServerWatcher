package com.logdashboard.web;

import com.logdashboard.analysis.AnalysisService;
import com.logdashboard.config.ConfigLoader;
import com.logdashboard.config.DashboardConfig;
import com.logdashboard.store.InfrastructureStore;
import com.logdashboard.store.IssueRepository;
import com.logdashboard.watcher.LogFileWatcher;

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
    private final IssueRepository issueStore;
    private final AnalysisService analysisService;
    private final DashboardConfig config;
    private InfrastructureStore infrastructureStore;
    private ConfigLoader configLoader;
    private LogFileWatcher logWatcher;
    private EventStreamServlet eventStreamServlet;
    
    public WebServer(int port, IssueRepository issueStore, AnalysisService analysisService, DashboardConfig config) {
        this.port = port;
        this.issueStore = issueStore;
        this.analysisService = analysisService;
        this.config = config;
        this.tomcat = new Tomcat();
    }
    
    /**
     * Sets the infrastructure store for server/VM management.
     */
    public void setInfrastructureStore(InfrastructureStore infrastructureStore) {
        this.infrastructureStore = infrastructureStore;
    }
    
    /**
     * Sets the config loader for configuration management.
     */
    public void setConfigLoader(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }
    
    /**
     * Sets the log file watcher for dynamic directory management.
     */
    public void setLogFileWatcher(LogFileWatcher logWatcher) {
        this.logWatcher = logWatcher;
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
        
        // Add Event Stream servlet (SSE) - create first for reference
        eventStreamServlet = new EventStreamServlet(issueStore);
        Tomcat.addServlet(context, "events", eventStreamServlet).setAsyncSupported(true);
        context.addServletMappingDecoded("/events", "events");
        
        // Add API servlet with reference to event stream for viewer tracking
        ApiServlet apiServlet = new ApiServlet(issueStore, analysisService, config);
        apiServlet.setEventStreamServlet(eventStreamServlet);
        Tomcat.addServlet(context, "api", apiServlet);
        context.addServletMappingDecoded("/api/*", "api");
        
        // Add Infrastructure API servlet for server/VM management
        if (infrastructureStore != null) {
            InfraServlet infraServlet = new InfraServlet(infrastructureStore);
            Tomcat.addServlet(context, "infra", infraServlet);
            context.addServletMappingDecoded("/infra/*", "infra");
        }
        
        // Add Configuration API servlet for watch directory management
        if (configLoader != null) {
            ConfigApiServlet configServlet = new ConfigApiServlet(configLoader, config, logWatcher);
            Tomcat.addServlet(context, "config", configServlet);
            context.addServletMappingDecoded("/config/*", "config");
        }
        
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
