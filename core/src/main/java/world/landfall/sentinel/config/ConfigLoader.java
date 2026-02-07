package world.landfall.sentinel.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigLoader {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static SentinelConfig loadConfig(Path dataDirectory, Logger logger) {
        // Ensure plugin data folder exists
        try {
            if (Files.notExists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
                logger.info("Created data directory at {}", dataDirectory);
            }
        } catch (IOException e) {
            logger.error("Could not create plugin data directory {}", dataDirectory, e);
            throw new RuntimeException("Failed to create data directory", e);
        }

        Path configPath = dataDirectory.resolve("config.json");
        try {
            // If config.json is missing, create it with defaults
            if (Files.notExists(configPath)) {
                SentinelConfig defaultConfig = new SentinelConfig();
                try (Writer writer = Files.newBufferedWriter(configPath)) {
                    gson.toJson(defaultConfig, writer);
                }
                logger.info("Created default config.json at {}", configPath);
                return defaultConfig;
            }

            // Otherwise read the existing file
            try (Reader reader = Files.newBufferedReader(configPath)) {
                return gson.fromJson(reader, SentinelConfig.class);
            }

        } catch (IOException e) {
            logger.error("Failed to load config from {}", configPath, e);
            throw new RuntimeException("Could not load configuration", e);
        }
    }
}
