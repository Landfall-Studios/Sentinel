package com.confect1on.sentinel.discord;

import com.confect1on.sentinel.db.DatabaseManager;
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

/**
 * Handles the /note command for adding internal staff notes to users.
 * Notes are not visible to the user but are logged for staff reference.
 */
public class NoteCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final ModerationManager moderationManager;
    private final String[] staffRoles;
    private final Logger logger;

    private final SlashCommandData commandData = Commands
            .slash("note", "Add an internal staff note to a user (not visible to them)")
            .addOption(OptionType.USER, "user", "User to add note about (Discord mention)", true)
            .addOption(OptionType.STRING, "note", "Internal note content", true);

    public NoteCommandListener(DatabaseManager db, ModerationManager moderationManager, String[] staffRoles, Logger logger) {
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
        if (!"note".equals(event.getName())) return;

        // Check staff permission
        if (!hasStaffPermission(event)) {
            event.reply("❌ You don't have permission to use this command.")
                .setEphemeral(true)
                .queue();
            return;
        }

        // Get options
        var userOpt = event.getOption("user");
        var noteOpt = event.getOption("note");

        if (userOpt == null || noteOpt == null) {
            event.reply("❌ User and note are required.")
                .setEphemeral(true)
                .queue();
            return;
        }

        String note = noteOpt.getAsString();

        // Defer reply (ephemeral - only audit channel should see the public embed)
        InteractionHook hook;
        try {
            hook = event.deferReply(true).complete();
        } catch (Exception ex) {
            logger.error("❌ Failed to defer /note interaction", ex);
            return;
        }

        // Find the user
        User user = userOpt.getAsUser();
        String discordId = user.getId();
        String displayName = user.getAsTag();

        // Record the note (internal only - no DM sent to user)
        long actionId = moderationManager.recordNote(discordId, note, event.getUser().getId());

        if (actionId > 0) {
            // Send success response with embed
            hook.sendMessageEmbeds(
                moderationManager.createNoteEmbed(discordId, note, event.getUser().getId(), actionId)
            ).queue();

            logger.info("📝 {} added note to {} (#{}) - {}",
                event.getUser().getAsTag(), displayName, actionId, note);
        } else {
            hook.sendMessage("❌ Failed to record note. Check logs for details.").queue();
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
