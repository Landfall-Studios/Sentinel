package world.landfall.sentinel.discord;

import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.LinkInfo;
import world.landfall.sentinel.db.QuarantineInfo;
import world.landfall.sentinel.moderation.ModerationManager;
import world.landfall.sentinel.util.DurationParser;
import world.landfall.sentinel.config.SentinelConfig;
import world.landfall.sentinel.context.PlatformAdapter;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Handles the /ban command for banning users from the Minecraft server using quarantine.
 */
public class BanCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final ModerationManager moderationManager;
    private final String[] staffRoles;
    private final String quarantineRoleId;
    private final PlatformAdapter platformAdapter;
    private final SentinelConfig config;
    private final Logger logger;

    private final SlashCommandData commandData = Commands
            .slash("ban", "Ban a user from the server")
            .addOption(OptionType.USER, "user", "User to ban", true)
            .addOption(OptionType.STRING, "duration", "Ban duration (e.g., 30m, 2h, 3d, 1w) - leave empty for permanent", false)
            .addOption(OptionType.STRING, "reason", "Reason for ban", false);

    public BanCommandListener(DatabaseManager db, ModerationManager moderationManager, String[] staffRoles, String quarantineRoleId, PlatformAdapter platformAdapter, SentinelConfig config, Logger logger) {
        this.db = db;
        this.moderationManager = moderationManager;
        this.staffRoles = staffRoles;
        this.quarantineRoleId = quarantineRoleId;
        this.platformAdapter = platformAdapter;
        this.config = config;
        this.logger = logger;
    }

    public SlashCommandData getCommandData() {
        return commandData;
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        if (!"ban".equals(event.getName())) return;

        // Check staff permission
        if (!hasStaffPermission(event)) {
            event.reply("❌ You don't have permission to use this command.")
                .setEphemeral(true)
                .queue();
            return;
        }

        // Get options
        var userOpt = event.getOption("user");
        var durationOpt = event.getOption("duration");
        var reasonOpt = event.getOption("reason");

        if (userOpt == null) {
            event.reply("❌ User is required.")
                .setEphemeral(true)
                .queue();
            return;
        }

        String reason = reasonOpt != null ? reasonOpt.getAsString() : "No reason provided";
        String durationStr = durationOpt != null ? durationOpt.getAsString() : null;

        // Parse duration for quarantine
        Instant expiresAt = null;
        String durationDisplay = "Permanent";

        if (durationStr != null && !durationStr.trim().isEmpty()) {
            if (!DurationParser.isValidDuration(durationStr)) {
                event.reply("❌ Invalid duration format. Use formats like: 30m, 2h, 3d, 1w")
                    .setEphemeral(true)
                    .queue();
                return;
            }
            expiresAt = DurationParser.parseDurationToExpiry(durationStr);
            durationDisplay = DurationParser.formatDuration(DurationParser.parseDurationToSeconds(durationStr));
        }

        // Find the user
        User targetUser = userOpt.getAsUser();
        String discordId = targetUser.getId();
        String displayName = targetUser.getAsTag();

        // Check if already banned (quarantined) - before deferring so we can make it ephemeral
        Optional<QuarantineInfo> existingQuarantine = db.getActiveQuarantine(discordId);
        if (existingQuarantine.isPresent()) {
            QuarantineInfo existing = existingQuarantine.get();
            String message;
            if (existing.isPermanent()) {
                message = "❌ " + displayName + " is already permanently banned.\n" +
                          "**Reason:** " + existing.reason() + "\n" +
                          "Use `/unban` to remove the existing ban first.";
            } else {
                message = "❌ " + displayName + " is already banned.\n" +
                          "**Reason:** " + existing.reason() + "\n" +
                          "**Time remaining:** " + existing.getFormattedTimeRemaining() + "\n" +
                          "Use `/unban` to remove the existing ban first.";
            }
            event.reply(message)
                .setEphemeral(true)
                .queue();
            return;
        }

        // Now defer reply (ephemeral - only audit channel should see the public embed)
        InteractionHook hook;
        try {
            hook = event.deferReply(true).complete();
        } catch (Exception ex) {
            logger.error("❌ Failed to defer /ban interaction", ex);
            return;
        }

        // Record the ban in moderation log with duration
        long actionId = moderationManager.recordBan(discordId, reason, event.getUser().getId(), durationDisplay);

        if (actionId > 0) {
            // Add quarantine (permanent or timed based on duration)
            if (quarantineRoleId != null && !quarantineRoleId.isBlank()) {
                boolean quarantineAdded = db.addQuarantine(discordId, reason, expiresAt, event.getUser().getId());

                if (quarantineAdded) {
                    // Apply Discord quarantine role
                    boolean roleApplied = applyDiscordQuarantineRole(event, discordId, displayName);

                    if (expiresAt != null) {
                        logger.info("🚫 Added temporary ban quarantine for {} until {}", discordId, expiresAt);
                    } else {
                        logger.info("🚫 Added permanent ban quarantine for {}", discordId);
                    }

                    // Kick the player if they're currently online
                    kickPlayerIfOnline(discordId, displayName, reason);

                    // Send success response with embed
                    hook.sendMessageEmbeds(
                        moderationManager.createBanEmbed(discordId, reason, event.getUser().getId(), actionId, durationDisplay)
                    ).queue();

                    logger.info("🔨 {} banned {} (#{}) for: {}",
                        event.getUser().getAsTag(), displayName, actionId, reason);
                } else {
                    hook.sendMessage("❌ Failed to add quarantine. Check logs for details.")
                        .queue();
                }
            } else {
                // No quarantine role configured, just record the ban
                hook.sendMessageEmbeds(
                    moderationManager.createBanEmbed(discordId, reason, event.getUser().getId(), actionId, durationDisplay)
                ).queue();

                logger.info("🔨 {} banned {} (#{}) for: {} (no quarantine role configured)",
                    event.getUser().getAsTag(), displayName, actionId, reason);
            }
        } else {
            hook.sendMessage("❌ Failed to record ban. Check logs for details.")
                .queue();
        }
    }

    private boolean hasStaffPermission(SlashCommandInteractionEvent event) {
        if (staffRoles == null || staffRoles.length == 0) {
            return false;
        }

        Guild guild = event.getGuild();
        if (guild == null) {
            return false;
        }

        Member member = event.getMember();
        if (member == null) {
            return false;
        }

        // Check if member has ban permission (admin/mod likely have this)
        if (member.hasPermission(Permission.BAN_MEMBERS)) {
            return true;
        }

        // Check staff roles
        for (String staffRoleId : staffRoles) {
            if (staffRoleId != null && !staffRoleId.isBlank()) {
                Role staffRole = guild.getRoleById(staffRoleId);
                if (staffRole != null && member.getRoles().contains(staffRole)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Applies the Discord quarantine role to a user.
     */
    private boolean applyDiscordQuarantineRole(SlashCommandInteractionEvent event, String discordId, String displayName) {
        if (quarantineRoleId == null || quarantineRoleId.isBlank()) {
            logger.warn("Attempted to apply Discord quarantine role, but quarantineRoleId is not configured.");
            return false;
        }

        try {
            // Find the quarantine role and guild
            Guild targetGuild = null;
            Role targetRole = null;

            for (Guild guild : event.getJDA().getGuilds()) {
                Role role = guild.getRoleById(quarantineRoleId);
                if (role != null) {
                    targetGuild = guild;
                    targetRole = role;
                    break;
                }
            }

            if (targetRole == null || targetGuild == null) {
                logger.error("Configured quarantine role with ID {} not found in any guild.", quarantineRoleId);
                return false;
            }

            // Retrieve the member
            Member member = targetGuild.retrieveMemberById(discordId).complete();

            // Add the role if it's not already present
            if (!member.getRoles().contains(targetRole)) {
                final String roleName = targetRole.getName();
                targetGuild.addRoleToMember(member, targetRole).queue(
                        success -> {
                            logger.info("🚫 Discord quarantine role {} applied to {} ({})", roleName, displayName, discordId);
                        },
                        failure -> {
                            logger.error("❌ Failed to apply Discord quarantine role {} to {} ({})", roleName, displayName, discordId, failure);
                        }
                );
                return true;
            } else {
                logger.debug("🚫 Discord quarantine role {} already applied to {} ({})", targetRole.getName(), displayName, discordId);
                return true;
            }
        } catch (Exception e) {
            logger.error("❌ Error applying Discord quarantine role for {} ({})", displayName, discordId, e);
            return false;
        }
    }

    /**
     * Kicks a player from all linked platforms if they are currently online.
     */
    private void kickPlayerIfOnline(String discordId, String discordUsername, String reason) {
        try {
            List<LinkInfo> links = db.findByDiscordId(discordId);
            if (links.isEmpty()) {
                logger.debug("No linked accounts found for Discord ID {} when trying to kick", discordId);
                return;
            }

            for (LinkInfo link : links) {
                platformAdapter.kickPlayer(link.uuid(), "BANNED FROM SERVER\n\nReason: " + reason);
                logger.info("Kicked player {} ({}) from server due to ban", link.uuid(), link.platform().displayName());
            }
        } catch (Exception e) {
            logger.error("Error while trying to kick banned player with Discord ID {}", discordId, e);
        }
    }
}
