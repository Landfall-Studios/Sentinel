package world.landfall.sentinel.util;

import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.config.SentinelConfig;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.List;

/**
 * Handles IP address logging for login attempts.
 * Used for moderation and compliance purposes.
 */
public class IpLogger {
    private final DatabaseManager db;
    private final SentinelConfig.Tos config;
    private final Logger logger;

    public IpLogger(DatabaseManager db, SentinelConfig.Tos config, Logger logger) {
        this.db = db;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Logs a login attempt with IP address.
     *
     * @param uuid The player UUID
     * @param discordId The Discord ID (can be null for unlinked players)
     * @param ipAddress The IP address
     * @param allowed Whether the login was allowed
     * @param denyReason The reason for denial (null if allowed)
     */
    public void logLogin(UUID uuid, String discordId, String ipAddress, boolean allowed, String denyReason) {
        if (!config.ipLogging) {
            return; // IP logging disabled
        }

        boolean success = db.logLoginIp(uuid, discordId, ipAddress, allowed, denyReason);
        if (!success) {
            logger.warn("Failed to log IP for UUID {} from {}", uuid, ipAddress);
        } else if (!allowed) {
            logger.debug("Logged denied login for {} from {} - Reason: {}", uuid, ipAddress, denyReason);
        }
    }

    /**
     * Gets recent login IPs for a player.
     * Useful for moderation and pattern detection.
     *
     * @param uuid The player UUID
     * @param limit Maximum number of records to return
     * @return List of recent login attempts
     */
    public List<DatabaseManager.LoginIpInfo> getRecentLogins(UUID uuid, int limit) {
        return db.getRecentLoginIps(uuid, limit);
    }

    /**
     * Formats IP information for display.
     *
     * @param ipInfo The IP info to format
     * @return Formatted string for Discord display
     */
    public String formatIpInfo(DatabaseManager.LoginIpInfo ipInfo) {
        String status = ipInfo.allowed() ? "Allowed" : "Denied";
        String timestamp = "<t:" + ipInfo.loginTime().getEpochSecond() + ":R>";

        StringBuilder formatted = new StringBuilder();
        formatted.append("**IP:** `").append(ipInfo.ipAddress()).append("`\n");
        formatted.append("**Time:** ").append(timestamp).append("\n");
        formatted.append("**Status:** ").append(status);

        if (!ipInfo.allowed() && ipInfo.denyReason() != null) {
            formatted.append("\n**Reason:** ").append(ipInfo.denyReason());
        }

        return formatted.toString();
    }

    /**
     * Checks if IP logging is enabled.
     *
     * @return true if IP logging is enabled
     */
    public boolean isEnabled() {
        return config.ipLogging;
    }
}
