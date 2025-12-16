package com.logdashboard;

import com.logdashboard.config.ConfigLoader;
import com.logdashboard.config.DashboardConfig;
import com.logdashboard.ui.DashboardFrame;
import com.logdashboard.watcher.LogFileWatcher;

import javax.swing.*;
import java.io.IOException;

/**
 * Main entry point for the Log Issue Dashboard application.
 * 
 * Usage: java -jar log-issue-dashboard.jar <config-folder>
 * 
 * The config folder should contain a dashboard-config.json file.
 * If it doesn't exist, a default configuration will be created.
 */
public class LogDashboardApp {
    
    private static final String VERSION = "1.0.0";
    
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
        
        System.out.println("Log Issue Dashboard v" + VERSION);
        System.out.println("Configuration folder: " + configFolder);
        
        // Load configuration
        DashboardConfig config;
        try {
            ConfigLoader loader = new ConfigLoader(configFolder);
            config = loader.loadConfig();
            
            if (config.getWatchPaths().isEmpty()) {
                System.out.println();
                System.out.println("WARNING: No watch paths configured!");
                System.out.println("Please edit: " + loader.getConfigFilePath());
                System.out.println("Add paths to the 'watchPaths' array in the configuration file.");
                System.out.println();
            }
        } catch (IOException e) {
            System.err.println("Error loading configuration: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }
        
        // Start the application on EDT
        final DashboardConfig finalConfig = config;
        SwingUtilities.invokeLater(() -> {
            try {
                startApplication(finalConfig);
            } catch (Exception e) {
                System.err.println("Error starting application: " + e.getMessage());
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, 
                    "Error starting application: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
    
    private static void startApplication(DashboardConfig config) {
        // Create and show the dashboard
        DashboardFrame dashboard = new DashboardFrame(config);
        
        // Create and start the file watcher
        LogFileWatcher watcher = new LogFileWatcher(
            config,
            dashboard::addIssue,
            dashboard::updateStatus
        );
        
        // Add shutdown hook to stop watcher gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            watcher.stop();
        }));
        
        // Start the watcher
        watcher.start();
        
        // Show the dashboard
        dashboard.setVisible(true);
        
        System.out.println("Dashboard started. Watching for log file changes...");
    }
    
    private static void printUsage() {
        System.out.println("Log Issue Dashboard v" + VERSION);
        System.out.println();
        System.out.println("A Java application that watches log files for exceptions/issues");
        System.out.println("and displays them in a real-time dashboard.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar log-issue-dashboard.jar <config-folder>");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  <config-folder>     Path to folder containing dashboard-config.json");
        System.out.println("  --help, -h          Show this help message");
        System.out.println("  --version, -v       Show version information");
        System.out.println("  --create-config <path>  Create a sample configuration file");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  The config folder should contain a 'dashboard-config.json' file.");
        System.out.println("  If it doesn't exist, a default configuration will be created.");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar log-issue-dashboard.jar /path/to/config");
        System.out.println("  java -jar log-issue-dashboard.jar ./config");
        System.out.println();
        System.out.println("Configuration file options:");
        System.out.println("  watchPaths          - List of directories/files to watch");
        System.out.println("  filePatterns        - File patterns to match (e.g., \"*.log\")");
        System.out.println("  exceptionPatterns   - Regex patterns to detect exceptions");
        System.out.println("  errorPatterns       - Regex patterns to detect errors");
        System.out.println("  warningPatterns     - Regex patterns to detect warnings");
        System.out.println("  pollingIntervalSeconds - How often to check for changes");
        System.out.println("  maxIssuesDisplayed  - Maximum issues to keep in memory");
        System.out.println();
    }
}
