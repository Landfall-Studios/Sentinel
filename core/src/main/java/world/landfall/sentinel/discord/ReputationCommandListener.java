package world.landfall.sentinel.discord;

import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.DatabaseManager.ReputationVote;
import world.landfall.sentinel.db.LinkInfo;
import world.landfall.sentinel.reputation.ReputationManager;
import world.landfall.sentinel.reputation.ReputationManager.ReputationResult;
import world.landfall.sentinel.reputation.ReputationManager.VoteResult;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class ReputationCommandListener extends ListenerAdapter {
    private final DatabaseManager db;
    private final ReputationManager repManager;
    private final Logger logger;

    private final SlashCommandData commandData = Commands
            .slash("rep", "Manage player reputation")
            .addSubcommands(
                new SubcommandData("view", "View a player's reputation")
                    .addOption(OptionType.STRING, "username", "Minecraft username to check", true),
                new SubcommandData("vote", "Vote on a player's reputation")
                    .addOption(OptionType.STRING, "username", "Minecraft username to vote on", true)
                    .addOption(OptionType.STRING, "vote", "Vote type: + or -", true)
                    .addOption(OptionType.STRING, "comment", "Optional comment about the player", false)
            );

    public ReputationCommandListener(DatabaseManager db, ReputationManager repManager, Logger logger) {
        this.db = db;
        this.repManager = repManager;
        this.logger = logger;
    }

    public SlashCommandData getCommandData() {
        return commandData;
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent evt) {
        if (!"rep".equals(evt.getName())) return;

        String subcommand = evt.getSubcommandName();
        if (subcommand == null) {
            evt.reply("Invalid command usage.").setEphemeral(true).queue();
            return;
        }

        // Defer reply
        InteractionHook hook;
        try {
            hook = evt.deferReply(true).complete();
        } catch (Exception ex) {
            logger.error("Failed to defer /rep interaction", ex);
            return;
        }

        switch (subcommand) {
            case "view" -> handleView(evt, hook);
            case "vote" -> handleVote(evt, hook);
            default -> hook.sendMessage("Unknown subcommand.").queue();
        }
    }

    private void handleView(SlashCommandInteractionEvent evt, InteractionHook hook) {
        var usernameOption = evt.getOption("username");
        if (usernameOption == null) {
            hook.sendMessage("Username parameter is required.").queue();
            return;
        }

        String username = usernameOption.getAsString();

        // Look up the player
        Optional<LinkInfo> linkInfo = db.findByUsername(username);
        if (linkInfo.isEmpty()) {
            hook.sendMessage(String.format("User '%s' is not linked or doesn't exist.", username)).queue();
            return;
        }

        String targetDiscordId = linkInfo.get().discordId();

        // Get reputation
        ReputationResult result = repManager.getReputation(targetDiscordId);

        // Build embed
        MessageEmbed embed = buildReputationEmbed(username, result);
        hook.sendMessageEmbeds(embed).queue();
    }

    private void handleVote(SlashCommandInteractionEvent evt, InteractionHook hook) {
        // Check if voter is linked
        String voterDiscordId = evt.getUser().getId();
        Optional<LinkInfo> voterLink = db.findByDiscordId(voterDiscordId);
        if (voterLink.isEmpty()) {
            hook.sendMessage("You must link your Minecraft account before voting. Use `/link` to link your account.").queue();
            return;
        }

        var usernameOption = evt.getOption("username");
        var voteOption = evt.getOption("vote");

        if (usernameOption == null || voteOption == null) {
            hook.sendMessage("Username and vote parameters are required.").queue();
            return;
        }

        String username = usernameOption.getAsString();
        String voteStr = voteOption.getAsString().toLowerCase();

        // Parse vote value
        int voteValue;
        if (voteStr.equals("+") || voteStr.equals("up")) {
            voteValue = 1;
        } else if (voteStr.equals("-") || voteStr.equals("down")) {
            voteValue = -1;
        } else {
            hook.sendMessage("Invalid vote type. Use `+` or `-` (or `up`/`down`).").queue();
            return;
        }

        // Get optional comment
        String comment = null;
        var commentOption = evt.getOption("comment");
        if (commentOption != null) {
            comment = commentOption.getAsString();
        }

        // Look up target player
        Optional<LinkInfo> targetLink = db.findByUsername(username);
        if (targetLink.isEmpty()) {
            hook.sendMessage(String.format("User '%s' is not linked or doesn't exist.", username)).queue();
            return;
        }

        String targetDiscordId = targetLink.get().discordId();

        // Submit vote
        VoteResult voteResult = repManager.submitVote(voterDiscordId, targetDiscordId, voteValue, comment);

        if (voteResult.success()) {
            // Show updated reputation
            ReputationResult repResult = repManager.getReputation(targetDiscordId);
            MessageEmbed embed = buildReputationEmbed(username, repResult);

            hook.sendMessage(voteResult.message())
                .addEmbeds(embed)
                .queue();
        } else {
            hook.sendMessage(voteResult.message()).queue();
        }
    }

    private MessageEmbed buildReputationEmbed(String username, ReputationResult result) {
        int displayScore = result.cache().displayScore();

        // Determine color based on score
        Color color;
        if (displayScore >= 50) {
            // Green gradient
            int greenIntensity = Math.min(255, 100 + (displayScore * 155 / 100));
            color = new Color(0, greenIntensity, 0);
        } else if (displayScore <= -50) {
            // Red gradient
            int redIntensity = Math.min(255, 100 + (Math.abs(displayScore) * 155 / 100));
            color = new Color(redIntensity, 0, 0);
        } else {
            // Gray for neutral
            color = Color.GRAY;
        }

        EmbedBuilder embed = new EmbedBuilder()
            .setTitle(String.format("Reputation for %s", username))
            .setColor(color)
            .addField("Score", String.format("**%d**", displayScore), true)
            .addField("Total Votes", String.valueOf(result.cache().totalVotesReceived()), true)
            .addField("Percentile", String.format("%.1f%%", result.cache().percentileRank()), true);

        // Build recent votes as a list of comments
        if (!result.recentVotes().isEmpty()) {
            StringBuilder commentsList = new StringBuilder();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy");

            for (ReputationVote vote : result.recentVotes()) {
                String dateStr = vote.votedAt().atZone(java.time.ZoneId.systemDefault()).format(formatter);
                boolean hasComment = vote.comment() != null && !vote.comment().isEmpty();

                // Symbol: bold if has comment, regular if not
                String symbol = vote.voteValue() > 0 ?
                    (hasComment ? "**+**" : "+") :
                    (hasComment ? "**-**" : "-");

                String commentStr = hasComment ? vote.comment() : "[no comment]";

                commentsList.append(String.format("\n%s %s - %s", symbol, dateStr, commentStr));
            }

            embed.addField("Recent Votes", commentsList.toString(), false);
        } else {
            embed.addField("Recent Votes", "No votes yet", false);
        }

        // Add cache info
        if (result.fromCache()) {
            embed.setFooter("From cache");
        } else {
            embed.setFooter("Freshly calculated");
        }

        return embed.build();
    }
}
