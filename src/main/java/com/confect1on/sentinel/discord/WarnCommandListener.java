package com.confect1on.sentinel.discord;

import com.confect1on.sentinel.db.DatabaseManager;
import com.confect1on.sentinel.db.LinkInfo;
import com.confect1on.sentinel.moderation.ModerationManager;
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
import java.util.Optional;

/**
 * Handles the /warn command for issuing warnings to users.
 */
public class WarnCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final ModerationManager moderationManager;
    private final String[] staffRoles;
    private final Logger logger;
    
    private final SlashCommandData commandData = Commands
            .slash("warn", "Issue a warning to a user")
            .addOption(OptionType.USER, "user", "User to warn (Discord mention)", true)
            .addOption(OptionType.STRING, "reason", "Reason for warning", true);
    
    public WarnCommandListener(DatabaseManager db, ModerationManager moderationManager, String[] staffRoles, Logger logger) {
        this.db = db;
        this.moderationManager = moderationManager;
        this.staffRoles = staffRoles;
        this.logger = logger;
    }
    
    public SlashCommandData getCommandData() {
        return commandData;
    }
    
    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        if (!"warn".equals(event.getName())) return;
        
        // Check staff permission
        if (!hasStaffPermission(event)) {
            event.reply("❌ You don't have permission to use this command.")
                .setEphemeral(true)
                .queue();
            return;
        }
        
        // Get options
        var userOpt = event.getOption("user");
        var reasonOpt = event.getOption("reason");
        
        if (userOpt == null || reasonOpt == null) {
            event.reply("❌ User and reason are required.")
                .setEphemeral(true)
                .queue();
            return;
        }
        
        String reason = reasonOpt.getAsString();
        
        // Defer reply (ephemeral - only audit channel should see the public embed)
        InteractionHook hook;
        try {
            hook = event.deferReply(true).complete();
        } catch (Exception ex) {
            logger.error("❌ Failed to defer /warn interaction", ex);
            return;
        }
        
        // Find the user
        User user = userOpt.getAsUser();
        String discordId = user.getId();
        String displayName = user.getAsTag();
        
        // Record the warning
        long actionId = moderationManager.recordWarning(discordId, reason, event.getUser().getId());
        
        if (actionId > 0) {
            // Try to DM the user
            try {
                User targetUser = event.getJDA().retrieveUserById(discordId).complete();
                if (targetUser != null) {
                    targetUser.openPrivateChannel().queue(channel -> {
                        channel.sendMessage(
                            "⚠ **You have received a warning**\n\n" +
                            "**Reason:** " + reason + "\n\n" +
                            "Please follow the server rules to avoid further action."
                        ).queue(
                            success -> logger.debug("Warning DM sent to {}", discordId),
                            error -> logger.debug("Could not DM warning to {} (DMs disabled?)", discordId)
                        );
                    });
                }
            } catch (Exception e) {
                logger.debug("Could not retrieve user {} for DM", discordId);
            }
            
            // Send success response with embed
            hook.sendMessageEmbeds(
                moderationManager.createWarningEmbed(discordId, reason, event.getUser().getId(), actionId)
            ).queue();
            
            logger.info("⚠ {} warned {} (#{}) for: {}", 
                event.getUser().getAsTag(), displayName, actionId, reason);
        } else {
            hook.sendMessage("❌ Failed to record warning. Check logs for details.").queue();
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
}