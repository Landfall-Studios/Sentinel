package world.landfall.sentinel.discord;

import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.LinkInfo;
import world.landfall.sentinel.db.QuarantineInfo;
import world.landfall.sentinel.moderation.ModerationManager;
import world.landfall.sentinel.config.SentinelConfig;
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
 * Handles the /unban command for removing bans (quarantines) from users.
 */
public class UnbanCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final ModerationManager moderationManager;
    private final Logger logger;
    private final String[] staffRoles;
    private final String quarantineRoleId;

    private final SlashCommandData commandData = Commands
            .slash("unban", "Remove a ban from a user")
            .addOption(OptionType.USER, "user", "User to unban", true)
            .addOption(OptionType.STRING, "reason", "Reason for unban", false);

    public UnbanCommandListener(DatabaseManager db, ModerationManager moderationManager, String[] staffRoles, String quarantineRoleId, Logger logger) {
        this.db = db;
        this.moderationManager = moderationManager;
        this.staffRoles = staffRoles;
        this.quarantineRoleId = quarantineRoleId;
        this.logger = logger;
    }

    public SlashCommandData getCommandData() {
        return commandData;
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        if (!"unban".equals(event.getName())) return;

        // Check if user has permission to use this command
        if (!hasStaffPermission(event)) {
            event.reply("❌ You don't have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        var userOpt = event.getOption("user");
        var reasonOpt = event.getOption("reason");

        if (userOpt == null) {
            event.reply("❌ You must specify a user.").setEphemeral(true).queue();
            return;
        }

        String reason = reasonOpt != null ? reasonOpt.getAsString() : "No reason provided";

        // Get Discord ID and username
        User targetUser = userOpt.getAsUser();
        String discordId = targetUser.getId();
        String displayName = targetUser.getAsTag();

        // Check if user is currently banned (quarantined) - before deferring so we can make it ephemeral
        Optional<QuarantineInfo> quarantine = db.getActiveQuarantine(discordId);

        if (quarantine.isEmpty()) {
            event.reply("ℹ️ " + displayName + " is not currently banned.")
                .setEphemeral(true)
                .queue();
            return;
        }

        // Now defer reply (ephemeral - only audit channel should see the public embed)
        InteractionHook hook;
        try {
            hook = event.deferReply(true).complete();
        } catch (Exception ex) {
            logger.error("❌ Failed to defer /unban interaction", ex);
            return;
        }

        try {

            // Remove quarantine from database
            boolean removed = db.removeQuarantine(discordId);

            if (removed) {
                // Remove Discord quarantine role
                boolean roleRemoved = removeDiscordQuarantineRole(event, discordId, displayName);

                // Record the unban in moderation log
                long actionId = moderationManager.recordUnban(discordId, reason, event.getUser().getId());

                // Send success response with embed
                if (actionId > 0) {
                    hook.sendMessageEmbeds(
                        moderationManager.createUnbanEmbed(discordId, reason, event.getUser().getId(), actionId, quarantine.get())
                    ).queue();

                    logger.info("✅ {} unbanned {} (#{}) - Reason: {}",
                        event.getUser().getAsTag(), displayName, actionId, reason);
                } else {
                    // Unban succeeded but logging failed
                    StringBuilder message = new StringBuilder();
                    message.append("✅ **Removed ban from ").append(displayName).append("**\n");
                    message.append("**Reason:** ").append(reason).append("\n");
                    if (roleRemoved) {
                        message.append("**Discord role:** ✅ Removed");
                    } else {
                        message.append("**Discord role:** ⚠️ Not found or already removed");
                    }
                    hook.sendMessage(message.toString()).queue();

                    logger.info("✅ {} unbanned {} (logging failed)", event.getUser().getAsTag(), displayName);
                }
            } else {
                hook.sendMessage("❌ Failed to remove ban from " + displayName).queue();
            }

        } catch (Exception e) {
            hook.sendMessage("❌ An error occurred while processing the command.").queue();
            logger.error("❌ Error in unban command", e);
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

    /**
     * Removes the Discord quarantine role from a user.
     */
    private boolean removeDiscordQuarantineRole(SlashCommandInteractionEvent event, String discordId, String displayName) {
        if (quarantineRoleId == null || quarantineRoleId.isBlank()) {
            logger.debug("No quarantine role configured, skipping role removal");
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
                logger.warn("Configured quarantine role with ID {} not found in any guild", quarantineRoleId);
                return false;
            }

            // Retrieve the member
            try {
                Member member = targetGuild.retrieveMemberById(discordId).complete();

                // Remove the role if present
                if (member.getRoles().contains(targetRole)) {
                    final String roleName = targetRole.getName();
                    targetGuild.removeRoleFromMember(member, targetRole).queue(
                            success -> {
                                logger.info("✅ Discord quarantine role {} removed from {} ({})", roleName, displayName, discordId);
                            },
                            failure -> {
                                logger.error("❌ Failed to remove Discord quarantine role {} from {} ({})", roleName, displayName, discordId, failure);
                            }
                    );
                    return true;
                } else {
                    logger.debug("Discord quarantine role not present on {} ({})", displayName, discordId);
                    return false;
                }
            } catch (Exception e) {
                // User might not be in the guild anymore
                logger.debug("Could not find member {} ({}) in guild - may have left", displayName, discordId);
                return false;
            }
        } catch (Exception e) {
            logger.error("❌ Error removing Discord quarantine role for {} ({})", displayName, discordId, e);
            return false;
        }
    }
}
