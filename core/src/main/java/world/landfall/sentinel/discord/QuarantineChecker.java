package world.landfall.sentinel.discord;

import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.QuarantineInfo;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles quarantine checking with support for both timed database quarantines and Discord roles.
 */
public class QuarantineChecker {
    private final DatabaseManager database;
    private final JDA jda;
    private final String quarantineRoleId;
    private final Logger logger;
    private final ScheduledExecutorService cleanupScheduler;

    public QuarantineChecker(DatabaseManager database, JDA jda, String quarantineRoleId, Logger logger) {
        this.database = database;
        this.jda = jda;
        this.quarantineRoleId = quarantineRoleId;
        this.logger = logger;

        // Start cleanup scheduler - runs every 10 minutes
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Sentinel-QuarantineCleanup");
            t.setDaemon(true);
            return t;
        });

        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredQuarantines, 1, 10, TimeUnit.MINUTES);
        logger.info("🧹 Quarantine cleanup scheduler started - will run every 10 minutes");
    }

    /**
     * Gets quarantine information for a Discord user.
     * Only checks database quarantines - Discord roles are NOT used for login blocking.
     * If the user has left Discord, removes them from the database.
     *
     * @param discordId The Discord ID to check
     * @return QuarantineInfo if quarantined in database, empty otherwise
     */
    public Optional<QuarantineInfo> getQuarantineInfo(String discordId) {
        if (discordId == null) {
            return Optional.empty();
        }

        try {
            // Only check database for timed quarantines
            // Discord roles are NOT used for login blocking anymore
            Optional<QuarantineInfo> dbQuarantine = database.getActiveQuarantine(discordId);
            if (dbQuarantine.isPresent()) {
                return dbQuarantine;
            }

            return Optional.empty();

        } catch (Exception e) {
            logger.error("🚫 Error checking quarantine status for Discord ID {}", discordId, e);
            return Optional.empty();
        }
    }

    /**
     * Checks if a Discord user is quarantined (backwards compatibility method).
     *
     * @param discordId The Discord ID to check
     * @return true if quarantined, false otherwise
     */
    public boolean isQuarantined(String discordId) {
        return getQuarantineInfo(discordId).isPresent();
    }

    /**
     * Checks if a Discord user is still in the server (any guild the bot is in).
     * If the user has left Discord, removes them from the database.
     *
     * @param discordId The Discord ID to check
     * @return true if the user is still in Discord, false if they left
     */
    public boolean isUserStillInDiscord(String discordId) {
        if (discordId == null) {
            return false;
        }

        try {
            // Check all guilds the bot is in
            for (Guild guild : jda.getGuilds()) {
                try {
                    guild.retrieveMemberById(discordId).complete();
                    return true; // Found the user in this guild
                } catch (Exception e) {
                    // User not found in this guild, continue to next guild
                }
            }

            // User not found in any guild - remove from database
            boolean removed = database.removeLinkByDiscordId(discordId);
            if (removed) {
                logger.info("🔗 Removed user {} from database - no longer in Discord server", discordId);
            }
            return false;

        } catch (Exception e) {
            logger.error("🔗 Error checking if Discord user {} is still in server", discordId, e);
            return true; // Default to assuming they're still there on error
        }
    }

    /**
     * Creates a formatted quarantine message for display to users.
     *
     * @param quarantine The quarantine information
     * @return A formatted message explaining the quarantine
     */
    public String formatQuarantineMessage(QuarantineInfo quarantine) {
        StringBuilder message = new StringBuilder();
        message.append("§c§l🚫 Your account is quarantined§r\n\n");
        message.append("§eReason: §f").append(quarantine.reason()).append("\n");

        if (quarantine.isPermanent()) {
            message.append("§eDuration: §c§lPermanent§r\n");
        } else {
            message.append("§eTime remaining: §a").append(quarantine.getFormattedTimeRemaining()).append("§r\n");
        }

        message.append("\n§7Contact an administrator for assistance.");
        return message.toString();
    }

    /**
     * Removes the Discord quarantine role from a user.
     *
     * @param discordId The Discord ID to remove the role from
     */
    private void removeDiscordQuarantineRole(String discordId) {
        if (quarantineRoleId == null || quarantineRoleId.isBlank()) {
            return; // No role configured
        }

        try {
            // Find the quarantine role and guild
            Guild targetGuild = null;
            Role targetRole = null;

            for (Guild guild : jda.getGuilds()) {
                Role role = guild.getRoleById(quarantineRoleId);
                if (role != null) {
                    targetGuild = guild;
                    targetRole = role;
                    break;
                }
            }

            if (targetRole == null || targetGuild == null) {
                logger.warn("Quarantine role with ID {} not found when trying to remove from Discord ID {}", quarantineRoleId, discordId);
                return;
            }

            // Try to find the member
            try {
                Member member = targetGuild.retrieveMemberById(discordId).complete();

                // Remove the role if they have it
                if (member.getRoles().contains(targetRole)) {
                    final String roleName = targetRole.getName(); // Final variable for lambda
                    final String memberName = member.getEffectiveName(); // Final variable for lambda
                    targetGuild.removeRoleFromMember(member, targetRole).queue(
                        success -> {
                            logger.info("🧹 Removed Discord quarantine role {} from {} ({})", roleName, memberName, discordId);
                        },
                        failure -> {
                            logger.error("Failed to remove Discord quarantine role {} from {} ({})", roleName, memberName, discordId, failure);
                        }
                    );
                } else {
                    logger.debug("Member {} ({}) doesn't have quarantine role {}, skipping removal", member.getEffectiveName(), discordId, targetRole.getName());
                }

            } catch (Exception e) {
                // Member not found or left Discord - this is fine, role is effectively removed
                logger.debug("Member with Discord ID {} not found in guild {} (likely left Discord), considering role removed", discordId, targetGuild.getName());
            }

        } catch (Exception e) {
            logger.error("Error removing Discord quarantine role from Discord ID {}", discordId, e);
        }
    }

    /**
     * Cleans up expired quarantine records from the database.
     * Also removes Discord roles for expired quarantines.
     */
    private void cleanupExpiredQuarantines() {
        try {
            logger.debug("🧹 Starting quarantine cleanup check...");

            // Get expired quarantines before removing them
            List<QuarantineInfo> expiredQuarantines = database.getExpiredQuarantines();

            if (expiredQuarantines.isEmpty()) {
                logger.debug("🧹 No expired quarantines found");
                return;
            }

            logger.info("🧹 Found {} expired quarantines to clean up", expiredQuarantines.size());

            // Remove Discord roles for expired quarantines
            int rolesRemoved = 0;
            for (QuarantineInfo quarantine : expiredQuarantines) {
                try {
                    removeDiscordQuarantineRole(quarantine.discordId());
                    rolesRemoved++;
                    logger.debug("🧹 Processed role removal for Discord ID: {}", quarantine.discordId());
                } catch (Exception e) {
                    logger.error("🧹 Failed to remove Discord role for Discord ID: {}", quarantine.discordId(), e);
                }
            }

            // Clean up database records
            database.cleanupExpiredQuarantines();

            logger.info("🧹 Cleanup complete: {} expired quarantines removed from database, {} Discord roles processed",
                expiredQuarantines.size(), rolesRemoved);

        } catch (Exception e) {
            logger.error("🧹 Critical error during quarantine cleanup", e);
        }
    }

    /**
     * Checks if a specific user's quarantine has expired and cleans it up immediately.
     * This is called during login to ensure expired quarantines are cleaned up right away.
     *
     * @param discordId The Discord ID to check and clean up
     * @return true if a quarantine was found and cleaned up, false if no cleanup was needed
     */
    public boolean checkAndCleanupExpiredQuarantine(String discordId) {
        if (discordId == null) {
            return false;
        }

        try {
            // Get the raw quarantine row (regardless of expiry status)
            Optional<QuarantineInfo> quarantine = database.getRawQuarantine(discordId);
            if (quarantine.isEmpty()) {
                return false; // No quarantine row at all
            }

            QuarantineInfo info = quarantine.get();
            if (info.isPermanent() || info.isActive()) {
                return false; // Still active, no cleanup needed
            }

            // Quarantine has expired, clean it up immediately
            logger.info("🧹 Found expired quarantine for Discord ID {} during login, cleaning up immediately", discordId);

            // Remove Discord role first
            removeDiscordQuarantineRole(discordId);

            // Remove from database
            boolean removed = database.removeQuarantine(discordId);
            if (removed) {
                logger.info("🧹 Cleaned up expired quarantine for Discord ID {} (was: {})", discordId, info.reason());
                return true;
            } else {
                logger.error("🧹 Failed to remove expired quarantine from database for Discord ID {}", discordId);
                return false;
            }

        } catch (Exception e) {
            logger.error("🧹 Error checking and cleaning up expired quarantine for Discord ID {}", discordId, e);
            return false;
        }
    }

    /**
     * Shuts down the quarantine checker and its cleanup scheduler.
     */
    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
