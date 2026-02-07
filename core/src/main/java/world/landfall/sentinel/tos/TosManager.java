package world.landfall.sentinel.tos;

import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.config.SentinelConfig;
import org.slf4j.Logger;

/**
 * Manages Terms of Service versioning and attestations.
 */
public class TosManager {
    private final DatabaseManager db;
    private final SentinelConfig.Tos config;
    private final Logger logger;

    public TosManager(DatabaseManager db, SentinelConfig.Tos config, Logger logger) {
        this.db = db;
        this.config = config;
        this.logger = logger;

        // Initialize ToS version in database if configured
        if (config.enforcement && config.version != null && !config.version.isBlank()) {
            initializeTosVersion();
        }
    }

    /**
     * Initializes the ToS version in the database.
     * Creates a basic ToS entry if none exists.
     */
    private void initializeTosVersion() {
        // Check if this specific version exists
        String existingContent = db.getTosVersionContent(config.version);
        if (existingContent == null) {
            // This version doesn't exist yet - create it
            String defaultContent;
            if (!config.content.isBlank()) {
                // Use the configured content from config file
                defaultContent = config.content;
                logger.info("Using ToS content from configuration file");
            } else if (!config.url.isBlank()) {
                // Fallback to URL reference
                defaultContent = "Please review our Terms of Service at: " + config.url;
                logger.info("No ToS content configured, using URL reference");
            } else {
                // No content or URL configured
                defaultContent = "Terms of Service content not configured. Please contact an administrator.";
                logger.warn("No ToS content or URL configured");
            }

            if (db.addTosVersion(config.version, defaultContent)) {
                logger.info("Initialized new ToS version {} in database", config.version);
            } else {
                logger.error("Failed to initialize ToS version {}", config.version);
            }
        } else {
            // Version exists - check if we should update the content
            if (!config.content.isBlank() && !config.content.equals(existingContent)) {
                logger.info("ToS version {} exists but content differs from config - keeping database version", config.version);
                logger.info("To update content, change the version number in config");
            }
        }
    }

    /**
     * Checks if a user has agreed to the current ToS version.
     *
     * @param discordId The Discord ID to check
     * @return true if they have agreed to the current version
     */
    public boolean hasAgreedToCurrentVersion(String discordId) {
        if (!config.enforcement) {
            return true; // ToS not enforced
        }

        String agreedVersion = db.getTosAttestation(discordId);
        return agreedVersion != null && agreedVersion.equals(config.version);
    }

    /**
     * Records a ToS attestation for a user.
     *
     * @param discordId The Discord ID attesting
     * @return true if successfully recorded
     */
    public boolean recordAttestation(String discordId) {
        if (!config.enforcement) {
            return true; // ToS not enforced
        }

        boolean success = db.addTosAttestation(discordId, config.version);
        if (success) {
            logger.info("Recorded ToS v{} attestation for Discord ID {}", config.version, discordId);
        } else {
            logger.error("Failed to record ToS attestation for Discord ID {}", discordId);
        }
        return success;
    }

    /**
     * Gets the current ToS version.
     *
     * @return The current version string
     */
    public String getCurrentVersion() {
        return config.version;
    }

    /**
     * Gets the ToS URL.
     *
     * @return The URL to the full ToS document
     */
    public String getTosUrl() {
        return config.url;
    }

    /**
     * Gets the ToS content for the current version from the database.
     *
     * @return The ToS content, or null if none
     */
    public String getTosContent() {
        return db.getTosVersionContent(config.version);
    }

    /**
     * Checks if ToS enforcement is enabled.
     *
     * @return true if ToS enforcement is enabled
     */
    public boolean isEnforced() {
        return config.enforcement;
    }

    /**
     * Gets the version a user has agreed to.
     *
     * @param discordId The Discord ID to check
     * @return The version they agreed to, or null if none
     */
    public String getUserAgreedVersion(String discordId) {
        return db.getTosAttestation(discordId);
    }
}
