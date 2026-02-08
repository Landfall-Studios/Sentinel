package world.landfall.sentinel.discord;

import world.landfall.sentinel.context.GamePlatform;
import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.LinkInfo;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.slf4j.Logger;

import java.util.List;

public class WhoIsCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final Logger logger;

    private final SlashCommandData commandData = Commands.slash("whois", "Lookup a link")
            .addOption(OptionType.USER,   "discord",   "Mention a Discord user",      false)
            .addOption(OptionType.STRING, "minecraft", "Minecraft username (cached)", false)
            .addOption(OptionType.STRING, "hytale",    "Hytale username (cached)",    false);

    public WhoIsCommandListener(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public SlashCommandData getCommandData() {
        return commandData;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent evt) {
        if (!evt.getName().equals("whois")) return;

        OptionMapping discordOpt   = evt.getOption("discord");
        OptionMapping minecraftOpt = evt.getOption("minecraft");
        OptionMapping hytaleOpt    = evt.getOption("hytale");

        // block til defer is sent to make sure we make it within 3s
        InteractionHook hook;
        try {
            hook = evt.deferReply(true).complete();
        } catch (Exception e) {
            logger.error("❌ Failed to defer interaction for /whois", e);
            return;
        }

        // Count how many options were provided
        int optionCount = (discordOpt != null ? 1 : 0) + (minecraftOpt != null ? 1 : 0) + (hytaleOpt != null ? 1 : 0);
        if (optionCount != 1) {
            hook.sendMessage("❗️ You must specify exactly one of: a Discord user, a Minecraft username, or a Hytale username.").queue();
            return;
        }

        if (discordOpt != null) {
            // Discord lookup — show all platforms
            List<LinkInfo> links = db.findByDiscordId(discordOpt.getAsUser().getId());
            if (links.isEmpty()) {
                hook.sendMessage("❌ No link found for that Discord user.").queue();
                return;
            }
            StringBuilder msg = new StringBuilder();
            msg.append("**Discord:** <@").append(links.get(0).discordId()).append(">");
            for (LinkInfo link : links) {
                msg.append(String.format("%n**%s:** %s (`%s`)", link.platform().displayName(), link.username(), link.uuid()));
            }
            hook.sendMessage(msg.toString()).queue();
        } else {
            // Username lookup — use platform-aware overload to avoid cross-platform ambiguity
            String username;
            GamePlatform lookupPlatform;
            if (minecraftOpt != null) {
                username = minecraftOpt.getAsString();
                lookupPlatform = GamePlatform.MINECRAFT;
            } else {
                username = hytaleOpt.getAsString();
                lookupPlatform = GamePlatform.HYTALE;
            }
            LinkInfo info = db.findByUsername(username, lookupPlatform).orElse(null);
            if (info == null) {
                hook.sendMessage("❌ No link found for that username.").queue();
                return;
            }
            // Also fetch all links for that Discord ID to show full picture
            List<LinkInfo> allLinks = db.findByDiscordId(info.discordId());
            StringBuilder msg = new StringBuilder();
            msg.append("**Discord:** <@").append(info.discordId()).append(">");
            for (LinkInfo link : allLinks) {
                msg.append(String.format("%n**%s:** %s (`%s`)", link.platform().displayName(), link.username(), link.uuid()));
            }
            hook.sendMessage(msg.toString()).queue();
        }
    }
}
