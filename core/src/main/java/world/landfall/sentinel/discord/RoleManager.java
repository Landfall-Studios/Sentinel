package world.landfall.sentinel.discord;

import world.landfall.sentinel.db.DatabaseManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages Discord roles for linked players.
 * Ensures all linked accounts have the configured role and handles rate limiting.
 */
public class RoleManager {
    private final DatabaseManager database;
    private final JDA jda;
    private final String roleId;
    private final Logger logger;

    private final ScheduledExecutorService executor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Rate limiting: process one role assignment every 2 seconds
    // This means ~1800 roles can be processed in 20 minutes (3600 seconds / 2 = 1800)
    private static final long RATE_LIMIT_DELAY_SECONDS = 2;

    public RoleManager(DatabaseManager database, JDA jda, String roleId, Logger logger) {
        this.database = database;
        this.jda = jda;
        this.roleId = roleId;
        this.logger = logger;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Sentinel-RoleManager");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the role synchronization process.
     * This will ensure all linked accounts have the configured role.
     */
    public void startRoleSynchronization() {
        if (roleId == null || roleId.isBlank()) {
            logger.info("🔗 Role synchronization disabled - no role ID configured");
            return;
        }

        if (isRunning.compareAndSet(false, true)) {
            logger.info("🔗 Starting role synchronization for linked players...");
            executor.execute(this::synchronizeRoles);
        }
    }

    /**
     * Adds a role to a newly linked player immediately (with rate limiting).
     */
    public void addRoleToLinkedPlayer(String discordId) {
        if (roleId == null || roleId.isBlank()) {
            return;
        }

        executor.schedule(() -> processRoleAssignment(discordId), 0, TimeUnit.SECONDS);
    }

    private void synchronizeRoles() {
        try {
            List<String> linkedDiscordIds = database.getAllLinkedDiscordIds();
            logger.info("🔗 Found {} linked accounts to synchronize", linkedDiscordIds.size());

            if (linkedDiscordIds.isEmpty()) {
                isRunning.set(false);
                return;
            }

            // Calculate total time for rate limiting info
            long totalTimeMinutes = (linkedDiscordIds.size() * RATE_LIMIT_DELAY_SECONDS) / 60;
            logger.info("🔗 Role synchronization will take approximately {} minutes", totalTimeMinutes);

            // Process each linked account with rate limiting
            for (int i = 0; i < linkedDiscordIds.size(); i++) {
                final String discordId = linkedDiscordIds.get(i);
                final int index = i;

                executor.schedule(() -> {
                    processRoleAssignment(discordId);

                    // Log progress every 20 assignments with percentage
                    if ((index + 1) % 20 == 0) {
                        double progressPercentage = ((double) (index + 1) / linkedDiscordIds.size()) * 100;
                        logger.info("🔗 Role sync progress: {}/{} completed ({}%)", index + 1, linkedDiscordIds.size(), String.format("%.2f", progressPercentage));
                    }

                    // Mark as finished when done
                    if (index == linkedDiscordIds.size() - 1) {
                        isRunning.set(false);
                        logger.info("🔗 Role synchronization completed for all {} linked accounts", linkedDiscordIds.size());
                    }
                }, i * RATE_LIMIT_DELAY_SECONDS, TimeUnit.SECONDS);
            }

        } catch (Exception e) {
            logger.error("🔗 Error during role synchronization", e);
            isRunning.set(false);
        }
    }

    private void processRoleAssignment(String discordId) {
        try {
            // Find the role across all guilds the bot is in
            Role targetRole = null;
            Guild targetGuild = null;
            Member targetMember = null;

            // First, find the role in any guild
            for (Guild guild : jda.getGuilds()) {
                Role role = guild.getRoleById(roleId);
                if (role != null) {
                    targetRole = role;
                    targetGuild = guild;
                    break;
                }
            }

            if (targetRole == null || targetGuild == null) {
                logger.warn("🔗 Role with ID {} not found in any guild", roleId);
                return;
            }

            // Find the member in the guild where we found the role using direct API call
            try {
                targetMember = targetGuild.retrieveMemberById(discordId).complete();
            } catch (Exception e) {
                // Member not found - remove from database and log
                boolean removed = database.removeLinkByDiscordId(discordId);
                if (removed) {
                    logger.info("🔗 Removed user {} from database - no longer in Discord server", discordId);
                }
                return;
            }

            // Check if member already has the role
            if (targetMember.getRoles().contains(targetRole)) {
                return; // Already has the role
            }

            // Add the role
            final Role finalRole = targetRole;
            final Guild finalGuild = targetGuild;
            final Member finalMember = targetMember;

            finalGuild.addRoleToMember(finalMember, finalRole)
                    .queue(
                            success -> logger.info("🔗 Added role {} to user {}", finalRole.getName(), finalMember.getEffectiveName()),
                            error -> {
                                if (error.getMessage().contains("Missing Permissions")) {
                                    logger.warn("🔗 Missing permissions to assign role {} in guild {}", finalRole.getName(), finalGuild.getName());
                                } else {
                                    logger.error("🔗 Failed to assign role {} to user {}: {}", finalRole.getName(), finalMember.getEffectiveName(), error.getMessage());
                                }
                            }
                    );

        } catch (Exception e) {
            logger.error("🔗 Error processing role assignment for Discord ID {}", discordId, e);
        }
    }

    /**
     * Shuts down the role manager and its executor.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
