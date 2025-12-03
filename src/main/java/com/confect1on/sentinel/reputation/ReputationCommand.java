package com.confect1on.sentinel.reputation;

import com.confect1on.sentinel.db.DatabaseManager;
import com.confect1on.sentinel.db.DatabaseManager.ReputationVote;
import com.confect1on.sentinel.db.LinkInfo;
import com.confect1on.sentinel.reputation.ReputationManager.ReputationResult;
import com.confect1on.sentinel.reputation.ReputationManager.VoteResult;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.List;

public class ReputationCommand implements SimpleCommand {

    private final DatabaseManager db;
    private final ReputationManager repManager;
    private final Logger logger;

    public ReputationCommand(DatabaseManager db, ReputationManager repManager, Logger logger) {
        this.db = db;
        this.repManager = repManager;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("This command can only be used by players", NamedTextColor.RED));
            return;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage:", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("  /rep <username> - View reputation", NamedTextColor.GRAY));
            player.sendMessage(Component.text("  /rep <username> +/- [comment] - Vote on reputation", NamedTextColor.GRAY));
            return;
        }

        // Check if player is linked
        String voterDiscordId = db.getDiscordId(player.getUniqueId());
        if (voterDiscordId == null) {
            player.sendMessage(Component.text("You must link your account to use this command!", NamedTextColor.RED));
            player.sendMessage(Component.text("Use /link in Discord to link your account", NamedTextColor.GRAY));
            return;
        }

        String targetUsername = args[0];

        // Check if this is a vote or view
        if (args.length == 1) {
            // View reputation
            handleView(player, targetUsername);
        } else {
            // Vote on reputation
            String voteStr = args[1];
            String comment = null;
            if (args.length > 2) {
                // Join remaining args as comment
                comment = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            }
            handleVote(player, voterDiscordId, targetUsername, voteStr, comment);
        }
    }

    private void handleView(Player player, String targetUsername) {
        // Look up target
        Optional<LinkInfo> targetLink = db.findByUsername(targetUsername);
        if (targetLink.isEmpty()) {
            player.sendMessage(Component.text("User '" + targetUsername + "' is not linked or doesn't exist.", NamedTextColor.RED));
            return;
        }

        String targetDiscordId = targetLink.get().discordId();

        // Get reputation (async to avoid blocking)
        CompletableFuture.runAsync(() -> {
            ReputationResult result = repManager.getReputation(targetDiscordId);

            // Build display
            int displayScore = result.cache().displayScore();

            // Determine color based on score
            NamedTextColor scoreColor;
            if (displayScore >= 50) {
                scoreColor = NamedTextColor.GREEN;
            } else if (displayScore >= 20) {
                scoreColor = NamedTextColor.YELLOW;
            } else if (displayScore >= -20) {
                scoreColor = NamedTextColor.GRAY;
            } else if (displayScore >= -50) {
                scoreColor = NamedTextColor.GOLD;
            } else {
                scoreColor = NamedTextColor.RED;
            }

            player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
            player.sendMessage(Component.text("Reputation for ", NamedTextColor.GRAY)
                .append(Component.text(targetUsername, NamedTextColor.WHITE, TextDecoration.BOLD)));

            player.sendMessage(Component.text("Score: ", NamedTextColor.GRAY)
                .append(Component.text(displayScore, scoreColor, TextDecoration.BOLD))
                .append(Component.text(" | Votes: ", NamedTextColor.GRAY))
                .append(Component.text(result.cache().totalVotesReceived(), NamedTextColor.WHITE))
                .append(Component.text(" | Percentile: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.1f%%", result.cache().percentileRank()), NamedTextColor.WHITE)));

            // Show recent votes with tooltips
            if (!result.recentVotes().isEmpty()) {
                // Build visualization with colored symbols - tooltips show date and comment
                Component votesViz = Component.empty();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy");

                for (int i = 0; i < result.recentVotes().size(); i++) {
                    ReputationVote vote = result.recentVotes().get(i);
                    boolean hasComment = vote.comment() != null && !vote.comment().isEmpty();

                    // Build tooltip text
                    String dateStr = vote.votedAt().atZone(java.time.ZoneId.systemDefault()).format(formatter);
                    String commentStr = hasComment ? vote.comment() : "[no comment]";
                    Component tooltip = Component.text(dateStr, NamedTextColor.GRAY)
                        .append(Component.newline())
                        .append(Component.text(commentStr, NamedTextColor.WHITE));

                    // Create symbol with tooltip
                    String symbol = vote.voteValue() > 0 ? "+" : "-";
                    NamedTextColor color = vote.voteValue() > 0 ? NamedTextColor.GREEN : NamedTextColor.RED;

                    Component symbolComponent;
                    if (hasComment) {
                        symbolComponent = Component.text(symbol, color, TextDecoration.BOLD)
                            .hoverEvent(HoverEvent.showText(tooltip));
                    } else {
                        symbolComponent = Component.text(symbol, color)
                            .hoverEvent(HoverEvent.showText(tooltip));
                    }

                    votesViz = votesViz.append(symbolComponent);

                    if (i < result.recentVotes().size() - 1) {
                        votesViz = votesViz.append(Component.text(" ", NamedTextColor.WHITE));
                    }
                }

                player.sendMessage(Component.text("Recent: ", NamedTextColor.GRAY).append(votesViz));
            } else {
                player.sendMessage(Component.text("No votes yet", NamedTextColor.DARK_GRAY));
            }

            player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        });
    }

    private void handleVote(Player player, String voterDiscordId, String targetUsername,
                           String voteStr, String comment) {
        // Parse vote value
        int voteValue;
        if (voteStr.equals("+") || voteStr.equalsIgnoreCase("up")) {
            voteValue = 1;
        } else if (voteStr.equals("-") || voteStr.equalsIgnoreCase("down")) {
            voteValue = -1;
        } else {
            player.sendMessage(Component.text("Invalid vote type. Use + or - (or up/down).", NamedTextColor.RED));
            return;
        }

        // Look up target
        Optional<LinkInfo> targetLink = db.findByUsername(targetUsername);
        if (targetLink.isEmpty()) {
            player.sendMessage(Component.text("User '" + targetUsername + "' is not linked or doesn't exist.", NamedTextColor.RED));
            return;
        }

        String targetDiscordId = targetLink.get().discordId();

        // Submit vote (async to avoid blocking)
        CompletableFuture.runAsync(() -> {
            VoteResult voteResult = repManager.submitVote(voterDiscordId, targetDiscordId, voteValue, comment);

            if (voteResult.success()) {
                player.sendMessage(Component.text("✓ ", NamedTextColor.GREEN)
                    .append(Component.text(voteResult.message(), NamedTextColor.WHITE)));

                // Show updated reputation
                ReputationResult repResult = repManager.getReputation(targetDiscordId);
                int newScore = repResult.cache().displayScore();

                NamedTextColor scoreColor = newScore >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED;
                player.sendMessage(Component.text("  New reputation for ", NamedTextColor.GRAY)
                    .append(Component.text(targetUsername, NamedTextColor.WHITE))
                    .append(Component.text(": ", NamedTextColor.GRAY))
                    .append(Component.text(newScore, scoreColor, TextDecoration.BOLD)));
            } else {
                player.sendMessage(Component.text("✗ ", NamedTextColor.RED)
                    .append(Component.text(voteResult.message(), NamedTextColor.WHITE)));
            }
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true; // Any linked player can use this
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        // Could implement username tab completion here
        return CompletableFuture.completedFuture(List.of());
    }
}
