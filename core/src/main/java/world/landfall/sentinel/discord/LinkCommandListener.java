package world.landfall.sentinel.discord;

import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.DatabaseManager.PendingClaim;
import world.landfall.sentinel.tos.TosManager;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.util.UUID;

public class LinkCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final Logger logger;
    private RoleManager roleManager;
    private TosManager tosManager;
    private TosCommandListener tosCommandListener;

    private final SlashCommandData commandData = Commands
            .slash("link", "Link your Minecraft account")
            .addOption(
                    net.dv8tion.jda.api.interactions.commands.OptionType.STRING,
                    "code", "Your link code", true
            );

    public LinkCommandListener(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public SlashCommandData getCommandData() {
        return commandData;
    }

    /**
     * Sets the role manager for assigning roles to newly linked players.
     */
    public void setRoleManager(RoleManager roleManager) {
        this.roleManager = roleManager;
    }

    /**
     * Sets the ToS manager for checking ToS requirements.
     */
    public void setTosManager(TosManager tosManager) {
        this.tosManager = tosManager;
    }

    /**
     * Sets the ToS command listener for showing ToS prompts.
     */
    public void setTosCommandListener(TosCommandListener tosCommandListener) {
        this.tosCommandListener = tosCommandListener;
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent evt) {
        if (!"link".equals(evt.getName())) return;

        var codeOption = evt.getOption("code");
        if (codeOption == null) {
            evt.reply("❌ Code parameter is required.").setEphemeral(true).queue();
            return;
        }
        String code = codeOption.getAsString();

        // block til defer is sent to make sure we make it within 3s
        InteractionHook hook;
        try {
            hook = evt.deferReply(true).complete();
        } catch (Exception ex) {
            logger.error("❌ Failed to defer /link interaction", ex);
            return;
        }

        // nowwww do the db work
        PendingClaim claim = db.claimPending(code);
        if (claim == null) {
            hook.sendMessage("❌ Invalid or expired code.").queue();
            return;
        }
        UUID uuid = claim.uuid();
        if (!db.addLink(uuid, evt.getUser().getId(), claim.platform())) {
            hook.sendMessage("❌ This Discord account is already linked on " + claim.platform().displayName() + "!").queue();
            return;
        }

        // Assign role to newly linked player
        if (roleManager != null) {
            roleManager.addRoleToLinkedPlayer(evt.getUser().getId());
        }

        String platformName = claim.platform().displayName();
        String successMsg = "✅ Your **" + platformName + "** account has been linked!";

        // Check if ToS acceptance is required
        if (tosManager != null && tosManager.isEnforced() && tosCommandListener != null) {
            // Check if they've already agreed to current ToS
            if (!tosManager.hasAgreedToCurrentVersion(evt.getUser().getId())) {
                // Show ToS prompt
                hook.sendMessage(successMsg)
                    .addEmbeds(tosCommandListener.createLinkTosPrompt(evt.getUser().getId()))
                    .setComponents(tosCommandListener.createTosButtons(evt.getUser().getId()))
                    .queue();
                logger.info("[Sentinel] Linked {} {} ↔ Discord {} - ToS prompt shown", platformName, uuid, evt.getUser().getId());
            } else {
                // Already agreed to ToS
                hook.sendMessage(successMsg).queue();
                logger.info("[Sentinel] Linked {} {} ↔ Discord {} - ToS already accepted", platformName, uuid, evt.getUser().getId());
            }
        } else {
            // ToS not enforced or managers not set
            hook.sendMessage(successMsg).queue();
            logger.info("[Sentinel] Linked {} {} ↔ Discord {}", platformName, uuid, evt.getUser().getId());
        }
    }
}
