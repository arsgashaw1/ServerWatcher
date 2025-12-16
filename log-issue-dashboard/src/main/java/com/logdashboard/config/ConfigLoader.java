package com.logdashboard.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.*;

/**
 * Loads configuration from a JSON file in the specified configuration folder.
 */
public class ConfigLoader {
    
    private static final String CONFIG_FILE_NAME = "dashboard-config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private final Path configFolder;
    
    public ConfigLoader(String configFolderPath) {
        this.configFolder = Paths.get(configFolderPath);
    }
    
    /**
     * Loads the configuration from the config folder.
     * If the config file doesn't exist, creates a default one.
     */
    public DashboardConfig loadConfig() throws IOException {
        Path configFile = configFolder.resolve(CONFIG_FILE_NAME);
        
        if (!Files.exists(configFolder)) {
            Files.createDirectories(configFolder);
            System.out.println("Created configuration folder: " + configFolder);
        }
        
        if (!Files.exists(configFile)) {
            // Create default configuration file
            DashboardConfig defaultConfig = new DashboardConfig();
            saveConfig(defaultConfig);
            System.out.println("Created default configuration file: " + configFile);
            System.out.println("Please edit the configuration file and add your watch paths.");
            return defaultConfig;
        }
        
        try (Reader reader = Files.newBufferedReader(configFile)) {
            DashboardConfig config = GSON.fromJson(reader, DashboardConfig.class);
            System.out.println("Loaded configuration from: " + configFile);
            return config;
        }
    }
    
    /**
     * Saves the configuration to the config folder.
     */
    public void saveConfig(DashboardConfig config) throws IOException {
        Path configFile = configFolder.resolve(CONFIG_FILE_NAME);
        
        if (!Files.exists(configFolder)) {
            Files.createDirectories(configFolder);
        }
        
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(config, writer);
        }
    }
    
    /**
     * Gets the path to the configuration file.
     */
    public Path getConfigFilePath() {
        return configFolder.resolve(CONFIG_FILE_NAME);
    }
    
    /**
     * Creates a sample configuration file with example settings.
     */
    public static void createSampleConfig(String outputPath) throws IOException {
        DashboardConfig sampleConfig = new DashboardConfig();
        sampleConfig.getWatchPaths().add("/path/to/your/logs");
        sampleConfig.getWatchPaths().add("/another/log/directory");
        
        Path outputFile = Paths.get(outputPath).resolve(CONFIG_FILE_NAME);
        
        try (Writer writer = Files.newBufferedWriter(outputFile)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(sampleConfig, writer);
        }
        
        System.out.println("Sample configuration created at: " + outputFile);
    }
}
