package com.logdashboard;

import com.logdashboard.analysis.AnalysisService;
import com.logdashboard.config.ConfigLoader;
import com.logdashboard.config.DashboardConfig;
import com.logdashboard.store.IssueStore;
import com.logdashboard.watcher.ConfigFileWatcher;
import com.logdashboard.watcher.LogFileWatcher;
import com.logdashboard.web.WebServer;

import java.io.IOException;

/**
 * Main entry point for the Log Issue Dashboard application.
 * 
 * This version runs an embedded Tomcat web server with a modern UI.
 * 
 * Usage: java -jar log-issue-dashboard.jar <config-folder> [port]
 * 
 * The config folder should contain a dashboard-config.json file.
 * If it doesn't exist, a default configuration will be created.
 */
public class LogDashboardApp {
    
    private static final String VERSION = "2.0.0";
    private static final int DEFAULT_PORT = 8080;
    
    private static ConfigLoader configLoader;
    private static IssueStore issueStore;
    private static AnalysisService analysisService;
    private static WebServer webServer;
    private static LogFileWatcher logWatcher;
    private static ConfigFileWatcher configWatcher;
    
    public static void main(String[] args) {
        // Check command line arguments
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }
        
        String configFolder = args[0];
        
        // Handle special commands
        if ("--help".equals(configFolder) || "-h".equals(configFolder)) {
            printUsage();
            System.exit(0);
        }
        
        if ("--version".equals(configFolder) || "-v".equals(configFolder)) {
            System.out.println("Log Issue Dashboard v" + VERSION);
            System.exit(0);
        }
        
        if ("--create-config".equals(configFolder)) {
            if (args.length < 2) {
                System.err.println("Error: Please specify output path for sample config");
                System.exit(1);
            }
            try {
                ConfigLoader.createSampleConfig(args[1]);
                System.out.println("Sample configuration created successfully.");
                System.exit(0);
            } catch (IOException e) {
                System.err.println("Error creating sample config: " + e.getMessage());
                System.exit(1);
            }
        }
        
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║           Log Issue Dashboard v" + VERSION + "                     ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration folder: " + configFolder);
        
        // Load configuration
        DashboardConfig config;
        try {
            configLoader = new ConfigLoader(configFolder);
            config = configLoader.loadConfig();
            
            boolean hasServers = config.getServers() != null && !config.getServers().isEmpty();
            boolean hasWatchPaths = config.getWatchPaths() != null && !config.getWatchPaths().isEmpty();
            
            if (!hasServers && !hasWatchPaths) {
                System.out.println();
                System.out.println("WARNING: No watch paths configured!");
                System.out.println("Please edit: " + configLoader.getConfigFilePath());
                System.out.println("Add servers to the 'servers' array or paths to the 'watchPaths' array.");
                System.out.println("The dashboard will automatically detect changes to the config file.");
                System.out.println();
            }
        } catch (IOException e) {
            System.err.println("Error loading configuration: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }
        
        // Determine port: command line arg > config file > default
        int port;
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + args[1] + ". Using config/default port.");
                port = config.getWebServerPort() > 0 ? config.getWebServerPort() : DEFAULT_PORT;
            }
        } else {
            // Use config file port if set, otherwise use default
            port = config.getWebServerPort() > 0 ? config.getWebServerPort() : DEFAULT_PORT;
        }
        
        // Start the application
        try {
            startApplication(config, port);
        } catch (Exception e) {
            System.err.println("Error starting application: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void startApplication(DashboardConfig config, int port) throws Exception {
        // Create the issue store
        issueStore = new IssueStore(config.getMaxIssuesDisplayed());
        
        // Create the analysis service
        analysisService = new AnalysisService(issueStore);
        
        // Create and start the web server
        webServer = new WebServer(port, issueStore, analysisService);
        webServer.start();
        
        // Create the log file watcher
        logWatcher = new LogFileWatcher(
            config,
            issueStore::addIssue,
            status -> System.out.println("[Watcher] " + status)
        );
        
        // Create the config file watcher
        configWatcher = new ConfigFileWatcher(
            configLoader,
            config,
            logWatcher::addServerPaths,
            status -> System.out.println("[Config] " + status)
        );
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("Shutting down...");
            logWatcher.stop();
            configWatcher.stop();
            webServer.stop();
            System.out.println("Goodbye!");
        }));
        
        // Start the watchers
        logWatcher.start();
        configWatcher.start();
        
        System.out.println("Log file watcher started.");
        System.out.println("Configuration file will be monitored for new servers.");
        System.out.println();
        System.out.println("Press Ctrl+C to stop the server.");
        
        // Keep the server running
        webServer.await();
    }
    
    private static void printUsage() {
        System.out.println("Log Issue Dashboard v" + VERSION);
        System.out.println();
        System.out.println("A web-based application that watches log files for exceptions/issues");
        System.out.println("and displays them in a real-time dashboard with analysis features.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar log-issue-dashboard.jar <config-folder> [port]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  <config-folder>     Path to folder containing dashboard-config.json");
        System.out.println("  [port]              HTTP port (default: " + DEFAULT_PORT + ")");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --help, -h          Show this help message");
        System.out.println("  --version, -v       Show version information");
        System.out.println("  --create-config <path>  Create a sample configuration file");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  The config folder should contain a 'dashboard-config.json' file.");
        System.out.println("  If it doesn't exist, a default configuration will be created.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar log-issue-dashboard.jar /path/to/config");
        System.out.println("  java -jar log-issue-dashboard.jar ./config 9090");
        System.out.println();
        System.out.println("Web Interface:");
        System.out.println("  Dashboard:    http://localhost:<port>/");
        System.out.println("  API:          http://localhost:<port>/api/");
        System.out.println("  Live Events:  http://localhost:<port>/events");
        System.out.println();
        System.out.println("API Endpoints:");
        System.out.println("  GET  /api/issues           - List all issues");
        System.out.println("  GET  /api/issues/recent    - Recent issues (last N minutes)");
        System.out.println("  GET  /api/stats            - Basic statistics");
        System.out.println("  GET  /api/stats/dashboard  - Dashboard statistics");
        System.out.println("  GET  /api/analysis         - Detailed analysis report");
        System.out.println("  GET  /api/analysis/anomalies - Detected anomalies");
        System.out.println("  GET  /api/servers          - List of servers");
        System.out.println("  POST /api/issues/{id}/acknowledge - Acknowledge an issue");
        System.out.println("  POST /api/issues/clear     - Clear all issues");
        System.out.println();
    }
}
