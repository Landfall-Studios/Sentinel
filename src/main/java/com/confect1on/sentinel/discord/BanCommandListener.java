package com.confect1on.sentinel.discord;

import com.confect1on.sentinel.db.DatabaseManager;
import com.confect1on.sentinel.db.LinkInfo;
import com.confect1on.sentinel.db.QuarantineInfo;
import com.confect1on.sentinel.moderation.ModerationManager;
import com.confect1on.sentinel.util.DurationParser;
import com.confect1on.sentinel.config.SentinelConfig;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
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
import java.util.Optional;

/**
 * Handles the /ban command for banning users from the Minecraft server using quarantine.
 */
public class BanCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final ModerationManager moderationManager;
    private final String[] staffRoles;
    private final String quarantineRoleId;
    private final ProxyServer proxyServer;
    private final SentinelConfig config;
    private final Logger logger;
    
    private final SlashCommandData commandData = Commands
            .slash("ban", "Ban a user from the server")
            .addOption(OptionType.USER, "user", "User to ban", true)
            .addOption(OptionType.STRING, "duration", "Ban duration (e.g., 30m, 2h, 3d, 1w) - leave empty for permanent", false)
            .addOption(OptionType.STRING, "reason", "Reason for ban", false);
    
    public BanCommandListener(DatabaseManager db, ModerationManager moderationManager, String[] staffRoles, String quarantineRoleId, ProxyServer proxyServer, SentinelConfig config, Logger logger) {
        this.db = db;
        this.moderationManager = moderationManager;
        this.staffRoles = staffRoles;
        this.quarantineRoleId = quarantineRoleId;
        this.proxyServer = proxyServer;
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
     * Kicks a player from the proxy if they are currently online.
     */
    private void kickPlayerIfOnline(String discordId, String discordUsername, String reason) {
        try {
            // Get the UUID from the Discord ID
            Optional<LinkInfo> linkInfo = db.findByDiscordId(discordId);
            if (linkInfo.isEmpty()) {
                logger.debug("🚫 No linked account found for Discord ID {} when trying to kick", discordId);
                return;
            }
            
            // Check if the player is currently online
            Optional<Player> onlinePlayer = proxyServer.getPlayer(linkInfo.get().uuid());
            if (onlinePlayer.isPresent()) {
                Player player = onlinePlayer.get();
                // Use the ban reason in the kick message
                Component kickMessage = Component.text()
                    .append(Component.text("⚠  BANNED FROM SERVER\n")
                        .color(net.kyori.adventure.text.format.NamedTextColor.RED)
                        .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    .append(Component.text("\n"))
                    .append(Component.text("Reason: ")
                        .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                    .append(Component.text(reason)
                        .color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                    .build();
                
                player.disconnect(kickMessage);
                logger.info("🚫 Kicked player {} ({}) from proxy due to ban", player.getUsername(), linkInfo.get().uuid());
            } else {
                logger.debug("🚫 Player {} ({}) is not currently online, no kick needed", discordUsername, linkInfo.get().uuid());
            }
        } catch (Exception e) {
            logger.error("🚫 Error while trying to kick banned player with Discord ID {}", discordId, e);
        }
    }
}