package world.landfall.sentinel.discord;

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

public class WhoIsCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final Logger logger;

    private final SlashCommandData commandData = Commands.slash("whois", "Lookup a link")
            .addOption(OptionType.USER,   "discord",   "Mention a Discord user",      false)
            .addOption(OptionType.STRING, "minecraft", "Minecraft username (cached)", false);

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

        // block til defer is sent to make sure we make it within 3s
        InteractionHook hook;
        try {
            hook = evt.deferReply(true).complete();
        } catch (Exception e) {
            logger.error("❌ Failed to defer interaction for /whois", e);
            return;
        }

        // proceed :)
        if ((discordOpt == null && minecraftOpt == null) ||
                (discordOpt != null && minecraftOpt != null)) {
            hook.sendMessage("❗️ You must specify *either* a Discord user *or* a Minecraft username.").queue();
            return;
        }

        LinkInfo info = discordOpt != null
                ? db.findByDiscordId(discordOpt.getAsUser().getId()).orElse(null)
                : db.findByUsername(minecraftOpt.getAsString()).orElse(null);

        if (info == null) {
            hook.sendMessage("❌ No link found for that identifier.").queue();
        } else {
            String msg = String.format(
                    "**Discord:** <@%s>%n**Minecraft:** %s (`%s`)",
                    info.discordId(), info.username(), info.uuid()
            );
            hook.sendMessage(msg).queue();
        }
    }
}
