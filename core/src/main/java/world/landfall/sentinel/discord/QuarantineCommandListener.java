package world.landfall.sentinel.discord;

import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.LinkInfo;
import world.landfall.sentinel.db.QuarantineInfo;
import world.landfall.sentinel.config.SentinelConfig;
import world.landfall.sentinel.util.DurationParser;
import world.landfall.sentinel.context.PlatformAdapter;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.Optional;
import net.dv8tion.jda.api.interactions.InteractionHook;

public class QuarantineCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final Logger logger;
    private final String quarantineRoleId;
    private final String[] staffRoles;
    private final PlatformAdapter platformAdapter;
    private final SentinelConfig config;

    private final SlashCommandData commandData = Commands
            .slash("quarantine", "Add a quarantine to a Discord user or show quarantine status")
            .addOption(OptionType.USER, "user", "Discord user to quarantine", true)
            .addOption(OptionType.STRING, "duration", "Quarantine duration (e.g., 1h, 3d, 1w)", false)
            .addOption(OptionType.STRING, "reason", "Reason for quarantine", false);

    public QuarantineCommandListener(DatabaseManager db, String quarantineRoleId, String[] staffRoles, PlatformAdapter platformAdapter, SentinelConfig config, Logger logger) {
        this.db = db;
        this.quarantineRoleId = quarantineRoleId;
        this.staffRoles = staffRoles;
        this.platformAdapter = platformAdapter;
        this.config = config;
        this.logger = logger;
    }

    public SlashCommandData getCommandData() {
        return commandData;
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        if (!"quarantine".equals(event.getName())) return;

        // Check if quarantine is configured
        if (quarantineRoleId == null || quarantineRoleId.isBlank()) {
            event.reply("❌ Quarantine role is not configured.").setEphemeral(true).queue();
            return;
        }

        // Check if user has permission to use this command
        if (!hasStaffPermission(event)) {
            event.reply("❌ You don't have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        var userOpt = event.getOption("user");
        var durationOpt = event.getOption("duration");
        var reasonOpt = event.getOption("reason");

        // Validate that exactly one target option is provided
        if (userOpt == null) {
            event.reply("❗️ You must specify a Discord user.").setEphemeral(true).queue();
            return;
        }

        // Defer reply since we'll be doing database and Discord API calls
        event.deferReply().queue(hook -> {
            try {
                // Get Discord ID and username based on input type
                String discordId = userOpt.getAsUser().getId();
                String displayName = userOpt.getAsUser().getAsTag();

                // Check current quarantine status
                Optional<QuarantineInfo> existingQuarantine = db.getActiveQuarantine(discordId);

                // If no parameters provided, show current status
                if (durationOpt == null && reasonOpt == null) {
                    showQuarantineStatus(hook, displayName, existingQuarantine);
                    return;
                }

                // If quarantine exists, show error - user needs to remove first
                if (existingQuarantine.isPresent()) {
                    hook.sendMessage("❌ " + displayName + " is already quarantined. Use `/unquarantine` to remove it first.").queue();
                    return;
                }

                // Add new quarantine
                String reason = reasonOpt != null ? reasonOpt.getAsString() : "No reason provided";
                String durationStr = durationOpt != null ? durationOpt.getAsString() : null;

                // Parse duration
                Instant expiresAt = null;
                String durationDisplay = "Permanent";

                if (durationStr != null && !durationStr.trim().isEmpty()) {
                    if (!DurationParser.isValidDuration(durationStr)) {
                        hook.sendMessage("❌ Invalid duration format. Use formats like: 30m, 2h, 3d, 1w").queue();
                        return;
                    }
                    expiresAt = DurationParser.parseDurationToExpiry(durationStr);
                    durationDisplay = DurationParser.formatDuration(DurationParser.parseDurationToSeconds(durationStr));
                }

                // Add quarantine to database
                boolean added = db.addQuarantine(discordId, reason, expiresAt, event.getUser().getId());

                if (added) {
                    // Apply Discord role
                    boolean roleApplied = applyDiscordQuarantineRole(event, discordId, displayName);

                    StringBuilder message = new StringBuilder();
                    message.append("🚫 **Added quarantine to ").append(displayName).append("**\n");
                    message.append("**Duration:** `").append(durationDisplay).append("`\n");
                    message.append("**Reason:** ").append(reason).append("\n");
                    if (roleApplied) {
                        message.append("**Discord role:** ✅ Applied");
                    } else {
                        message.append("**Discord role:** ❌ Failed to apply");
                    }

                    hook.sendMessage(message.toString()).queue();
                    logger.info("🚫 {} quarantined {} for {} ({})", event.getUser().getAsTag(), displayName, durationDisplay, reason);

                    // Kick the player if they're currently online
                    kickPlayerIfOnline(discordId, displayName);
                } else {
                    hook.sendMessage("❌ Failed to add quarantine to " + displayName).queue();
                }

            } catch (Exception e) {
                hook.sendMessage("❌ An error occurred while processing the command.").queue();
                logger.error("🚫 Error in quarantine command", e);
            }
        });
    }

    /**
     * Shows the current quarantine status of a user.
     */
    private void showQuarantineStatus(InteractionHook hook, String displayName,
                                    Optional<QuarantineInfo> quarantine) {
        StringBuilder message = new StringBuilder();

        if (quarantine.isPresent()) {
            QuarantineInfo info = quarantine.get();
            message.append("🚫 **").append(displayName).append(" is quarantined**\n\n");
            message.append("**Reason:** ").append(info.reason()).append("\n");
            message.append("**Created:** <t:").append(info.createdAt().getEpochSecond()).append(":R>\n");
            message.append("**Created by:** <@").append(info.createdBy()).append(">\n");

            if (info.isPermanent()) {
                message.append("**Duration:** `Permanent`\n");
            } else {
                message.append("**Duration:** `").append(info.getFormattedTimeRemaining()).append("`\n");
                message.append("**Expires:** <t:").append(info.expiresAt().getEpochSecond()).append(":R>\n");
            }

            // Removed Discord role status as it's no longer tracked
        } else {
            message.append("✅ **").append(displayName).append(" is not quarantined**\n\n");
            message.append("**Status:** Clean - no quarantine found\n");
        }

        hook.sendMessage(message.toString()).queue();
    }

    private boolean hasStaffPermission(SlashCommandInteractionEvent event) {
        // If no staff roles are configured, fail shut
        if (staffRoles == null || staffRoles.length == 0) {
            return false;
        }

        // Get guild and member with null checks
        Guild guild = event.getGuild();
        if (guild == null) {
            return false;
        }

        Member member = event.getMember();
        if (member == null) {
            return false;
        }

        // Check if the member has any of the staff roles
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
                final String roleName = targetRole.getName(); // Final variable for lambda
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
                return true; // Role already applied
            }
        } catch (Exception e) {
            logger.error("❌ Error applying Discord quarantine role for {} ({})", displayName, discordId, e);
            return false;
        }
    }

    /**
     * Kicks a player from the proxy if they are currently online.
     * This is called when a quarantine role is added to ensure immediate enforcement.
     */
    private void kickPlayerIfOnline(String discordId, String discordUsername) {
        try {
            Optional<LinkInfo> linkInfo = db.findByDiscordId(discordId);
            if (linkInfo.isEmpty()) {
                logger.debug("No linked account found for Discord ID {} when trying to kick", discordId);
                return;
            }

            platformAdapter.kickPlayer(linkInfo.get().uuid(), config.discord.quarantineMessage);
            logger.info("Kicked player {} from server due to quarantine", linkInfo.get().uuid());
        } catch (Exception e) {
            logger.error("Error while trying to kick quarantined player with Discord ID {}", discordId, e);
        }
    }
}
