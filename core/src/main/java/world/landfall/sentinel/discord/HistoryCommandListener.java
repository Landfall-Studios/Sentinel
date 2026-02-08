package world.landfall.sentinel.discord;

import world.landfall.sentinel.context.GamePlatform;
import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.LinkInfo;
import world.landfall.sentinel.moderation.ModerationManager;
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
import java.util.List;
import java.util.Optional;

/**
 * Handles the /history command for viewing moderation history.
 */
public class HistoryCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final ModerationManager moderationManager;
    private final String[] staffRoles;
    private final Logger logger;

    private final SlashCommandData commandData = Commands
            .slash("history", "View moderation history for a user")
            .addOption(OptionType.USER, "user", "User to check (Discord mention)", false)
            .addOption(OptionType.STRING, "minecraft", "Minecraft username", false);

    public HistoryCommandListener(DatabaseManager db, ModerationManager moderationManager, String[] staffRoles, Logger logger) {
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
        if (!"history".equals(event.getName())) return;

        // Check staff permission
        if (!hasStaffPermission(event)) {
            event.reply("❌ You don't have permission to use this command.")
                .setEphemeral(true)
                .queue();
            return;
        }

        // Get options
        var userOpt = event.getOption("user");
        var minecraftOpt = event.getOption("minecraft");

        if ((userOpt == null && minecraftOpt == null) || (userOpt != null && minecraftOpt != null)) {
            event.reply("❗ You must specify either a Discord user or a Minecraft username, not both.")
                .setEphemeral(true)
                .queue();
            return;
        }

        // Defer reply (ephemeral - this is sensitive moderation history)
        InteractionHook hook;
        try {
            hook = event.deferReply(true).complete();
        } catch (Exception ex) {
            logger.error("❌ Failed to defer /history interaction", ex);
            return;
        }

        // Find the user
        String discordId;
        String displayName;

        if (userOpt != null) {
            User user = userOpt.getAsUser();
            discordId = user.getId();
            displayName = user.getAsTag();
        } else {
            // Look up by Minecraft username
            String mcUsername = minecraftOpt.getAsString();
            Optional<LinkInfo> linkInfo = db.findByUsername(mcUsername, GamePlatform.MINECRAFT);

            if (linkInfo.isEmpty()) {
                hook.sendMessage("❌ No linked account found for Minecraft user: " + mcUsername).queue();
                return;
            }

            discordId = linkInfo.get().discordId();
            displayName = mcUsername + " (MC)";
        }

        // Get moderation history
        List<DatabaseManager.ModerationAction> history = moderationManager.getHistory(discordId);

        // Send history embed
        hook.sendMessageEmbeds(
            moderationManager.createHistoryEmbed(discordId, history)
        ).queue();

        logger.info("📋 {} viewed moderation history for {} ({} actions)",
            event.getUser().getAsTag(), displayName, history.size());
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
