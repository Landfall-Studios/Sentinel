package com.confect1on.sentinel.moderation;

import com.confect1on.sentinel.db.DatabaseManager;
import com.confect1on.sentinel.db.LinkInfo;
import com.confect1on.sentinel.db.QuarantineInfo;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Manages moderation actions including notes, warnings, bans, and audit logging.
 */
public class ModerationManager {
    private final DatabaseManager db;
    private final String auditChannelId;
    private final Logger logger;
    private JDA jda;
    
    public ModerationManager(DatabaseManager db, String auditChannelId, Logger logger) {
        this.db = db;
        this.auditChannelId = auditChannelId;
        this.logger = logger;
    }
    
    /**
     * Sets the JDA instance for sending audit messages.
     */
    public void setJDA(JDA jda) {
        this.jda = jda;
    }
    
    /**
     * Records a staff note (internal, not visible to user) and sends audit embed.
     */
    public long recordNote(String discordId, String note, String issuedBy) {
        // Look up linked Minecraft account if exists
        Optional<LinkInfo> linkInfo = db.findByDiscordId(discordId);
        String minecraftUuid = linkInfo.map(info -> info.uuid().toString()).orElse(null);

        // Store in database
        long actionId = db.addModerationAction(discordId, minecraftUuid, "NOTE", note, issuedBy);

        if (actionId > 0) {
            logger.info("📝 Staff note #{} added to Discord ID {} by {}: {}", actionId, discordId, issuedBy, note);

            // Send audit embed
            sendAuditEmbed(createNoteEmbed(discordId, note, issuedBy, actionId));
        }

        return actionId;
    }

    /**
     * Records a warning and sends audit embed.
     */
    public long recordWarning(String discordId, String reason, String issuedBy) {
        // Look up linked Minecraft account if exists
        Optional<LinkInfo> linkInfo = db.findByDiscordId(discordId);
        String minecraftUuid = linkInfo.map(info -> info.uuid().toString()).orElse(null);

        // Store in database
        long actionId = db.addModerationAction(discordId, minecraftUuid, "WARN", reason, issuedBy);

        if (actionId > 0) {
            logger.info("⚠ Warning #{} issued to Discord ID {} by {}: {}", actionId, discordId, issuedBy, reason);

            // Send audit embed
            sendAuditEmbed(createWarningEmbed(discordId, reason, issuedBy, actionId));
        }

        return actionId;
    }
    
    /**
     * Records a ban and sends audit embed.
     */
    public long recordBan(String discordId, String reason, String issuedBy) {
        return recordBan(discordId, reason, issuedBy, null);
    }

    /**
     * Records a ban and sends audit embed with optional duration.
     */
    public long recordBan(String discordId, String reason, String issuedBy, String duration) {
        // Look up linked Minecraft account if exists
        Optional<LinkInfo> linkInfo = db.findByDiscordId(discordId);
        String minecraftUuid = linkInfo.map(info -> info.uuid().toString()).orElse(null);

        // Store in database with duration
        long actionId = db.addModerationAction(discordId, minecraftUuid, "BAN", reason, issuedBy, duration);

        if (actionId > 0) {
            logger.info("🔨 Ban #{} issued to Discord ID {} by {}: {}", actionId, discordId, issuedBy, reason);

            // Send audit embed with duration
            sendAuditEmbed(createBanEmbed(discordId, reason, issuedBy, actionId, duration));
        }

        return actionId;
    }
    
    /**
     * Records an unban and sends audit embed.
     */
    public long recordUnban(String discordId, String reason, String issuedBy) {
        // Look up linked Minecraft account if exists
        Optional<LinkInfo> linkInfo = db.findByDiscordId(discordId);
        String minecraftUuid = linkInfo.map(info -> info.uuid().toString()).orElse(null);
        
        // Store in database
        long actionId = db.addModerationAction(discordId, minecraftUuid, "UNBAN", reason, issuedBy);
        
        if (actionId > 0) {
            logger.info("✅ Unban #{} issued to Discord ID {} by {}: {}", actionId, discordId, issuedBy, reason);
            
            // Send audit embed
            sendAuditEmbed(createUnbanEmbed(discordId, reason, issuedBy, actionId, null));
        }
        
        return actionId;
    }
    
    /**
     * Creates a note embed with blue theme.
     */
    public MessageEmbed createNoteEmbed(String discordId, String note, String issuedBy, long actionId) {
        return new EmbedBuilder()
            .setTitle("📝  **STAFF NOTE ADDED**")
            .setColor(new Color(88, 101, 242)) // Discord blurple
            .addField("User", "<@" + discordId + ">", true)
            .addField("Audit Number", String.valueOf(actionId), true)
            .addField("", "", false) // Spacer
            .addField("Note", note, false)
            .addField("Added by", "<@" + issuedBy + ">", true)
            .addField("Time", "<t:" + Instant.now().getEpochSecond() + ":F>", true)
            .setTimestamp(Instant.now())
            .setFooter("Sentinel Moderation • Internal Note", null)
            .build();
    }

    /**
     * Creates a warning embed with yellow theme.
     */
    public MessageEmbed createWarningEmbed(String discordId, String reason, String issuedBy, long actionId) {
        return new EmbedBuilder()
            .setTitle("⚠  **WARNING ISSUED**")
            .setColor(new Color(255, 204, 0)) // Yellow
            .addField("User", "<@" + discordId + ">", true)
            .addField("Audit Number", String.valueOf(actionId), true)
            .addField("", "", false) // Spacer
            .addField("Reason", reason, false)
            .addField("Issued by", "<@" + issuedBy + ">", true)
            .addField("Time", "<t:" + Instant.now().getEpochSecond() + ":F>", true)
            .setTimestamp(Instant.now())
            .setFooter("Sentinel Moderation", null)
            .build();
    }
    
    /**
     * Creates a ban embed with red theme.
     */
    public MessageEmbed createBanEmbed(String discordId, String reason, String issuedBy, long actionId) {
        return createBanEmbed(discordId, reason, issuedBy, actionId, null);
    }
    
    /**
     * Creates a ban embed with red theme and optional duration.
     */
    public MessageEmbed createBanEmbed(String discordId, String reason, String issuedBy, long actionId, String duration) {
        EmbedBuilder builder = new EmbedBuilder()
            .setTitle("🔨  **USER BANNED**")
            .setColor(new Color(237, 66, 69)) // Red
            .addField("User", "<@" + discordId + ">", true)
            .addField("Audit Number", String.valueOf(actionId), true);
        
        if (duration != null && !duration.isEmpty()) {
            builder.addField("Duration", duration, true);
        } else {
            builder.addField("Duration", "Permanent", true);
        }
        
        builder.addField("Reason", reason, false)
            .addField("Issued by", "<@" + issuedBy + ">", true)
            .addField("Time", "<t:" + Instant.now().getEpochSecond() + ":F>", true)
            .setTimestamp(Instant.now())
            .setFooter("Sentinel Moderation", null);
        
        return builder.build();
    }
    
    /**
     * Creates an unban embed with green theme.
     */
    public MessageEmbed createUnbanEmbed(String discordId, String reason, String issuedBy, long actionId, QuarantineInfo previousBan) {
        EmbedBuilder builder = new EmbedBuilder()
            .setTitle("✅  **USER UNBANNED**")
            .setColor(new Color(87, 242, 135)) // Green
            .addField("User", "<@" + discordId + ">", true)
            .addField("Audit Number", String.valueOf(actionId), true);
        
        if (previousBan != null) {
            String previousReason = previousBan.reason();
            if (previousReason != null && !previousReason.isEmpty()) {
                builder.addField("Original Ban Reason", previousReason, false);
            }
            if (!previousBan.isPermanent()) {
                builder.addField("Time Remaining", previousBan.getFormattedTimeRemaining(), true);
            }
        }
        
        builder.addField("Unban Reason", reason, false)
            .addField("Unbanned by", "<@" + issuedBy + ">", true)
            .addField("Time", "<t:" + Instant.now().getEpochSecond() + ":F>", true)
            .setTimestamp(Instant.now())
            .setFooter("Sentinel Moderation", null);
        
        return builder.build();
    }
    
    /**
     * Creates a history embed for a user's moderation actions.
     */
    public MessageEmbed createHistoryEmbed(String discordId, List<DatabaseManager.ModerationAction> actions) {
        EmbedBuilder builder = new EmbedBuilder()
            .setTitle("📋  **MODERATION HISTORY**")
            .setColor(new Color(88, 101, 242)) // Discord blurple
            .setDescription("User: <@" + discordId + ">");
        
        if (actions.isEmpty()) {
            builder.addField("✅ Clean Record", "No moderation actions found for this user.", false);
        } else {
            int notes = 0;
            int warnings = 0;
            int bans = 0;
            int unbans = 0;

            for (DatabaseManager.ModerationAction action : actions) {
                if ("NOTE".equals(action.actionType())) {
                    notes++;
                } else if ("WARN".equals(action.actionType())) {
                    warnings++;
                } else if ("BAN".equals(action.actionType())) {
                    bans++;
                } else if ("UNBAN".equals(action.actionType())) {
                    unbans++;
                }

                String emoji = "NOTE".equals(action.actionType()) ? "📝" :
                              "WARN".equals(action.actionType()) ? "⚠" :
                              "BAN".equals(action.actionType()) ? "🔨" : "✅";
                String title = emoji + " " + action.actionType() + " #" + action.id();

                StringBuilder valueBuilder = new StringBuilder();
                String label = "NOTE".equals(action.actionType()) ? "Note" : "Reason";
                valueBuilder.append("**").append(label).append(":** ").append(action.reason()).append("\n");

                // Add duration for bans
                if ("BAN".equals(action.actionType()) && action.duration() != null) {
                    valueBuilder.append("**Duration:** ").append(action.duration()).append("\n");
                }

                valueBuilder.append("**By:** <@").append(action.issuedBy()).append(">\n");
                valueBuilder.append("**Time:** <t:").append(action.issuedAt().getEpochSecond()).append(":R>");

                builder.addField(title, valueBuilder.toString(), false);

                // Discord embed field limit
                if (builder.getFields().size() >= 20) {
                    builder.addField("...", "And " + (actions.size() - 20) + " more actions", false);
                    break;
                }
            }

            // Add summary
            String summary = "Total: " + notes + " notes, " + warnings + " warnings, " + bans + " bans";
            if (unbans > 0) {
                summary += ", " + unbans + " unbans";
            }
            builder.setFooter(summary, null);
        }
        
        builder.setTimestamp(Instant.now());
        return builder.build();
    }
    
    /**
     * Sends an embed to the audit channel if configured.
     */
    private void sendAuditEmbed(MessageEmbed embed) {
        if (jda == null || auditChannelId == null || auditChannelId.isBlank()) {
            return;
        }
        
        try {
            TextChannel channel = jda.getTextChannelById(auditChannelId);
            if (channel != null) {
                channel.sendMessageEmbeds(embed).queue(
                    success -> logger.debug("Audit embed sent to channel {}", auditChannelId),
                    error -> logger.error("Failed to send audit embed to channel {}", auditChannelId, error)
                );
            } else {
                logger.warn("Audit channel {} not found", auditChannelId);
            }
        } catch (Exception e) {
            logger.error("Error sending audit embed", e);
        }
    }
    
    /**
     * Gets moderation history for a user.
     */
    public List<DatabaseManager.ModerationAction> getHistory(String discordId) {
        return db.getModerationHistory(discordId);
    }
}