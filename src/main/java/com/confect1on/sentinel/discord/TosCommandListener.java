package com.confect1on.sentinel.discord;

import com.confect1on.sentinel.db.DatabaseManager;
import com.confect1on.sentinel.tos.TosManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the /tos command for viewing and accepting Terms of Service.
 */
public class TosCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final TosManager tosManager;
    private final String auditChannelId;
    private final Logger logger;
    
    private final SlashCommandData commandData = Commands
            .slash("tos", "View and accept the Terms of Service");
    
    public TosCommandListener(DatabaseManager db, TosManager tosManager, String auditChannelId, Logger logger) {
        this.db = db;
        this.tosManager = tosManager;
        this.auditChannelId = auditChannelId;
        this.logger = logger;
    }
    
    public SlashCommandData getCommandData() {
        return commandData;
    }
    
    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        if (!"tos".equals(event.getName())) return;
        
        String userId = event.getUser().getId();
        
        // Defer reply (ephemeral - ToS status is personal)
        InteractionHook hook;
        try {
            hook = event.deferReply(true).complete();
        } catch (Exception ex) {
            logger.error("❌ Failed to defer /tos interaction", ex);
            return;
        }
        
        // Check if ToS enforcement is enabled
        if (!tosManager.isEnforced()) {
            hook.sendMessage("ℹ️ Terms of Service acceptance is not currently required.").queue();
            return;
        }
        
        // Check current attestation status
        String agreedVersion = tosManager.getUserAgreedVersion(userId);
        String currentVersion = tosManager.getCurrentVersion();
        boolean hasAgreedToCurrent = agreedVersion != null && agreedVersion.equals(currentVersion);
        boolean hasAgreedToOlder = agreedVersion != null && !agreedVersion.equals(currentVersion);
        
        // Build the ToS embed
        MessageEmbed embed = buildTosEmbed(hasAgreedToCurrent, hasAgreedToOlder, agreedVersion, currentVersion);
        
        // Build action rows with buttons
        List<ActionRow> actionRows = new ArrayList<>();
        
        // Show "I Agree" button if they haven't agreed to the current version
        if (!hasAgreedToCurrent) {
            List<Button> buttons = new ArrayList<>();
            buttons.add(Button.success("tos_agree_" + userId, "✅ I Agree to v" + currentVersion));
            
            if (!tosManager.getTosUrl().isBlank()) {
                buttons.add(Button.link(tosManager.getTosUrl(), "📄 View Full Terms"));
            }
            
            actionRows.add(ActionRow.of(buttons));
        } else if (!tosManager.getTosUrl().isBlank()) {
            // Only show link if they've already agreed
            actionRows.add(ActionRow.of(
                Button.link(tosManager.getTosUrl(), "📄 View Full Terms")
            ));
        }
        
        // Send the response
        if (actionRows.isEmpty()) {
            hook.sendMessageEmbeds(embed).queue();
        } else {
            hook.sendMessageEmbeds(embed)
                .setComponents(actionRows)
                .queue();
        }
    }
    
    @Override
    public void onButtonInteraction(@Nonnull ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();
        
        // Check if this is a ToS agreement button
        if (!buttonId.startsWith("tos_agree_")) return;
        
        // Extract the user ID from the button ID
        String expectedUserId = buttonId.substring("tos_agree_".length());
        String actualUserId = event.getUser().getId();
        
        // Verify the user clicking is the one who initiated the command
        if (!expectedUserId.equals(actualUserId)) {
            event.reply("❌ This button is not for you. Please run `/tos` yourself.")
                .setEphemeral(true)
                .queue();
            return;
        }
        
        // Record the attestation
        if (tosManager.recordAttestation(actualUserId)) {
            // Update the original message to show success
            MessageEmbed successEmbed = new EmbedBuilder()
                .setTitle("🎉 Success! Terms Accepted")
                .setDescription("**Thank you!** You have successfully agreed to our Terms of Service.\n\n" +
                    "✅ **You can now join the server and start playing!**")
                .setColor(new Color(87, 242, 135)) // Mint green
                .setThumbnail("https://cdn.discordapp.com/emojis/1034495861350395904.png")
                .addField("📌 Version Accepted", "```✅ v" + tosManager.getCurrentVersion() + "```", true)
                .addField("🕐 Timestamp", "<t:" + Instant.now().getEpochSecond() + ":F>", true)
                .addField("", "", false) // Spacer
                .addField("💡 Quick Tips", 
                    "• You can review the terms anytime with `/tos`\n" +
                    "• Your agreement is saved permanently\n" +
                    "• Join the server to start playing!", false)
                .setFooter("Welcome to the server! • Sentinel ToS", "https://cdn.discordapp.com/emojis/1034495861350395904.png")
                .setTimestamp(Instant.now())
                .build();
            
            event.editMessageEmbeds(successEmbed)
                .setComponents() // Remove buttons
                .queue();
            
            // Send audit embed if channel is configured
            sendAuditEmbed(event, actualUserId, tosManager.getCurrentVersion());
            
            logger.info("✅ User {} agreed to ToS version {}", actualUserId, tosManager.getCurrentVersion());
        } else {
            event.reply("❌ Failed to record your agreement. Please try again or contact an administrator.")
                .setEphemeral(true)
                .queue();
        }
    }
    
    /**
     * Builds the ToS embed based on the user's attestation status.
     */
    private MessageEmbed buildTosEmbed(boolean hasAgreedToCurrent, boolean hasAgreedToOlder, String agreedVersion, String currentVersion) {
        EmbedBuilder builder = new EmbedBuilder();
        
        if (hasAgreedToCurrent) {
            // User has already agreed to current version
            builder.setTitle("✅ Terms of Service Accepted")
                .setDescription("You're all set! You have already agreed to the current Terms of Service.")
                .setColor(new Color(87, 242, 135)) // Mint green
                .addField("📊 Status", "```✅ ACCEPTED```", true)
                .addField("📌 Version", "```v" + currentVersion + "```", true)
                .addField("🕐 Agreed", "<t:" + Instant.now().getEpochSecond() + ":R>", true);
        } else if (hasAgreedToOlder) {
            // User agreed to an older version - needs to re-agree
            String tosContent = tosManager.getTosContent();
            if (tosContent == null || tosContent.isBlank()) {
                tosContent = "Please review and accept the updated terms.";
            }
            
            builder.setTitle("🔄 Terms of Service Update Required")
                .setDescription("**Important:** Our Terms of Service have been updated. Please review and accept the new terms to continue playing on the server.")
                .setColor(new Color(255, 170, 0)) // Bright orange
                .addField("📜 Updated Terms", tosContent, false)
                .addField("⬅️ Previous Version", "```diff\n- v" + agreedVersion + " (outdated)\n```", true)
                .addField("➡️ New Version", "```diff\n+ v" + currentVersion + " (current)\n```", true)
                .addField("", "", false) // Spacer
                .addField("⚡ Action Required", "**Click the button below to accept the updated terms:**", false);
        } else {
            // User has never agreed
            String tosContent = tosManager.getTosContent();
            if (tosContent == null || tosContent.isBlank()) {
                tosContent = "Please review and accept our Terms of Service to continue.";
            }
            
            builder.setTitle("📜 Terms of Service Agreement Required")
                .setDescription("**Welcome!** Before you can play on our server, you must review and accept our Terms of Service.")
                .setColor(new Color(88, 101, 242)) // Discord blurple
                .addField("📋 Terms of Service v" + currentVersion, tosContent, false)
                .addField("", "━━━━━━━━━━━━━━━━━━━━━━━━━━━", false) // Visual separator
                .addField("✨ What happens next?", 
                    "• Click **I Agree** to accept the terms\n" +
                    "• You'll be able to join the server immediately\n" +
                    "• You can review the terms anytime with `/tos`", false)
                .addField("⚡ Action Required", "**Click the green button below to accept:**", false);
        }
        
        builder.setTimestamp(Instant.now())
            .setFooter("Sentinel ToS System • Version " + currentVersion, "https://cdn.discordapp.com/emojis/1034495861350395904.png");
        
        return builder.build();
    }
    
    /**
     * Creates a ToS prompt embed for newly linked users.
     * This is shown automatically after successful linking.
     */
    public MessageEmbed createLinkTosPrompt(String userId) {
        String currentVersion = tosManager.getCurrentVersion();
        String tosContent = tosManager.getTosContent();
        if (tosContent == null || tosContent.isBlank()) {
            tosContent = "Please review and accept our Terms of Service to continue.";
        }
        
        return new EmbedBuilder()
            .setTitle("🔗 Account Linked Successfully!")
            .setDescription("**Great news!** Your account has been linked.\n\n" +
                "**One more step:** Please accept our Terms of Service to start playing.")
            .setColor(new Color(88, 101, 242)) // Discord blurple
            .setThumbnail("https://cdn.discordapp.com/emojis/1162071679324180490.png")
            .addField("📋 Terms of Service v" + currentVersion, tosContent, false)
            .addField("", "━━━━━━━━━━━━━━━━━━━━━━━━━━━", false)
            .addField("⚠️ Important", 
                "**You must accept these terms to join the server**\n" +
                "This is a one-time requirement for all players.", false)
            .setTimestamp(Instant.now())
            .setFooter("Click the green button below to continue • Sentinel", null)
            .build();
    }
    
    /**
     * Creates action buttons for ToS acceptance.
     */
    public ActionRow createTosButtons(String userId) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.success("tos_agree_" + userId, "✅ I Agree to the Terms"));
        
        if (!tosManager.getTosUrl().isBlank()) {
            buttons.add(Button.link(tosManager.getTosUrl(), "📄 View Full Terms"));
        }
        
        return ActionRow.of(buttons);
    }
    
    /**
     * Sends a ToS agreement audit embed to the configured channel.
     */
    private void sendAuditEmbed(ButtonInteractionEvent event, String userId, String version) {
        if (auditChannelId == null || auditChannelId.isBlank()) {
            return; // No audit channel configured
        }
        
        try {
            TextChannel auditChannel = event.getJDA().getTextChannelById(auditChannelId);
            if (auditChannel == null) {
                logger.warn("ToS audit channel {} not found", auditChannelId);
                return;
            }
            
            // Get user info
            String username = event.getUser().getAsTag();
            String userMention = "<@" + userId + ">";
            
            // Create audit embed
            MessageEmbed auditEmbed = new EmbedBuilder()
                .setTitle("✅  **TERMS ACCEPTED**")
                .setColor(new Color(87, 242, 135)) // Green
                .addField("User", userMention, true)
                .addField("Username", username, true)
                .addField("Discord ID", userId, true)
                .addField("Version", "v" + version, true)
                .addField("Accepted", "<t:" + Instant.now().getEpochSecond() + ":F>", true)
                .setTimestamp(Instant.now())
                .setFooter("Sentinel ToS Audit", null)
                .build();
            
            auditChannel.sendMessageEmbeds(auditEmbed).queue(
                success -> logger.debug("ToS audit embed sent for user {}", userId),
                error -> logger.error("Failed to send ToS audit embed", error)
            );
        } catch (Exception e) {
            logger.error("Error sending ToS audit embed", e);
        }
    }
}