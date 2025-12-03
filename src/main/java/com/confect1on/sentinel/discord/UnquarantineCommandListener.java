package com.confect1on.sentinel.discord;

import com.confect1on.sentinel.db.DatabaseManager;
import com.confect1on.sentinel.db.LinkInfo;
import com.confect1on.sentinel.db.QuarantineInfo;
import com.confect1on.sentinel.config.SentinelConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.util.Optional;

public class UnquarantineCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final Logger logger;
    private final String[] staffRoles;
    private final String quarantineRoleId;

    private final SlashCommandData commandData = Commands
            .slash("unquarantine", "Remove a quarantine from a Discord user")
            .addOption(OptionType.USER, "user", "Discord user to remove quarantine from", true);

    public UnquarantineCommandListener(DatabaseManager db, String[] staffRoles, String quarantineRoleId, Logger logger) {
        this.db = db;
        this.staffRoles = staffRoles;
        this.quarantineRoleId = quarantineRoleId;
        this.logger = logger;
    }

    public SlashCommandData getCommandData() {
        return commandData;
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        if (!"unquarantine".equals(event.getName())) return;

        // Check if user has permission to use this command
        if (!hasStaffPermission(event)) {
            event.reply("❌ You don't have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        var userOpt = event.getOption("user");
        if (userOpt == null) {
            event.reply("❌ User parameter is required.").setEphemeral(true).queue();
            return;
        }

        // Defer reply since we'll be doing database calls
        event.deferReply().queue(hook -> {
            try {
                String discordId = userOpt.getAsUser().getId();
                String displayName = userOpt.getAsUser().getAsTag();

                // Check if user is quarantined
                Optional<QuarantineInfo> quarantine = db.getActiveQuarantine(discordId);
                
                if (quarantine.isEmpty()) {
                    hook.sendMessage("❌ " + displayName + " is not currently quarantined.").queue();
                    return;
                }

                // Remove quarantine
                boolean removed = db.removeQuarantine(discordId);
                
                if (removed) {
                    // Remove Discord role
                    boolean roleRemoved = removeDiscordQuarantineRole(event, discordId, displayName);
                    
                    QuarantineInfo info = quarantine.get();
                    StringBuilder message = new StringBuilder();
                    message.append("✅ **Removed quarantine from ").append(displayName).append("**\n");
                    message.append("**Previous reason:** ").append(info.reason()).append("\n");
                    if (!info.isPermanent()) {
                        message.append("**Remaining time:** `").append(info.getFormattedTimeRemaining()).append("`\n");
                    }
                    if (roleRemoved) {
                        message.append("**Discord role:** ✅ Removed");
                    } else {
                        message.append("**Discord role:** ❌ Failed to remove");
                    }
                    
                    hook.sendMessage(message.toString()).queue();
                    logger.info("✅ {} removed quarantine from {} (was: {})", 
                        event.getUser().getAsTag(), displayName, info.reason());
                } else {
                    hook.sendMessage("❌ Failed to remove quarantine from " + displayName + ". Please try again.").queue();
                    logger.error("Failed to remove quarantine from Discord ID {}", discordId);
                }

            } catch (Exception e) {
                hook.sendMessage("❌ An error occurred while processing the command.").queue();
                logger.error("🚫 Error in unquarantine command", e);
            }
        });
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

    private boolean removeDiscordQuarantineRole(SlashCommandInteractionEvent event, String discordId, String displayName) {
        if (quarantineRoleId == null || quarantineRoleId.isBlank()) {
            logger.warn("Quarantine role ID not configured, skipping Discord role removal for {}", displayName);
            return false;
        }

        Guild guild = event.getGuild();
        if (guild == null) {
            logger.error("Guild is null when trying to remove Discord quarantine role for {}", displayName);
            return false;
        }

        Role quarantineRole = guild.getRoleById(quarantineRoleId);
        if (quarantineRole == null) {
            logger.error("Quarantine role with ID {} not found for {}", quarantineRoleId, displayName);
            return false;
        }

        Member member = guild.getMemberById(discordId);
        if (member == null) {
            logger.error("Member with Discord ID {} not found for {}", discordId, displayName);
            return false;
        }

                 if (member.getRoles().contains(quarantineRole)) {
             try {
                 guild.removeRoleFromMember(member, quarantineRole).queue();
                 logger.info("✅ Removed Discord quarantine role {} from {}", quarantineRole.getName(), displayName);
                 return true;
             } catch (Exception e) {
                 logger.error("Failed to remove Discord quarantine role {} from {}", quarantineRole.getName(), displayName, e);
                 return false;
             }
         } else {
             logger.info("Discord quarantine role {} already removed for {}", quarantineRole.getName(), displayName);
             return false;
         }
    }
} 