package world.landfall.sentinel.reputation;

import world.landfall.sentinel.config.SentinelConfig;
import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.DatabaseManager.ReputationCache;
import world.landfall.sentinel.db.DatabaseManager.ReputationVote;
import world.landfall.sentinel.db.DatabaseManager.ReputationVoterStats;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Handles scheduled tasks for the reputation system:
 * - Cache refresh for stale scores
 * - Consensus tracking for voter credibility
 */
public class ReputationScheduler {
    private final DatabaseManager db;
    private final ReputationManager repManager;
    private final SentinelConfig.Reputation config;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;

    public ReputationScheduler(DatabaseManager db, ReputationManager repManager,
                              SentinelConfig.Reputation config, Logger logger) {
        this.db = db;
        this.repManager = repManager;
        this.config = config;
        this.logger = logger;

        // Start scheduled tasks
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Sentinel-ReputationScheduler");
            t.setDaemon(true);
            return t;
        });

        // Cache refresh task - runs every hour (configurable)
        scheduler.scheduleAtFixedRate(
            this::refreshStaleCache,
            config.scheduleRefreshMinutes,
            config.scheduleRefreshMinutes,
            TimeUnit.MINUTES
        );

        // Consensus tracking task - runs daily
        scheduler.scheduleAtFixedRate(
            this::updateConsensusTracking,
            60, // Initial delay: 1 hour
            1440, // Period: 24 hours
            TimeUnit.MINUTES
        );

        // Percentile rank update task - runs every hour
        scheduler.scheduleAtFixedRate(
            this::updateAllPercentiles,
            30, // Initial delay: 30 minutes
            60, // Period: 1 hour
            TimeUnit.MINUTES
        );

        logger.info("Reputation scheduler started - cache refresh every {} minutes, consensus tracking daily",
            config.scheduleRefreshMinutes);
    }

    /**
     * Refresh cache for users with stale scores or recent votes.
     */
    private void refreshStaleCache() {
        try {
            logger.debug("Starting reputation cache refresh...");

            List<ReputationCache> allCaches = db.getAllReputationScores();
            Instant staleThreshold = Instant.now().minus(Duration.ofMinutes(config.cacheStaleMinutes));
            int refreshed = 0;

            for (ReputationCache cache : allCaches) {
                if (cache.lastCalculated().isBefore(staleThreshold)) {
                    // Cache is stale - check if there are recent votes
                    List<ReputationVote> votes = db.getVotesForTarget(cache.discordId());

                    // Only recalculate if there are votes (avoid unnecessary calculations)
                    if (!votes.isEmpty()) {
                        boolean hasRecentVotes = votes.stream()
                            .anyMatch(v -> v.votedAt().isAfter(cache.lastCalculated()));

                        if (hasRecentVotes) {
                            repManager.calculateReputation(cache.discordId());
                            refreshed++;
                        }
                    }
                }
            }

            if (refreshed > 0) {
                logger.info("Cache refresh complete: {} reputations recalculated", refreshed);
            } else {
                logger.debug("Cache refresh complete: no stale caches needed refreshing");
            }

        } catch (Exception e) {
            logger.error("Error during reputation cache refresh", e);
        }
    }

    /**
     * Update consensus tracking for voter credibility.
     * Checks votes that are 30+ days old and compares voter's vote with current consensus.
     */
    private void updateConsensusTracking() {
        try {
            logger.debug("Starting consensus tracking update...");

            Instant consensusThreshold = Instant.now().minus(Duration.ofDays(config.consensusDays));

            // Get all voters who have stats
            List<ReputationCache> allScores = db.getAllReputationScores();

            // Track updates needed
            Map<String, ConsensusUpdate> voterUpdates = new HashMap<>();

            // For each user with reputation, check old votes
            for (ReputationCache cache : allScores) {
                List<ReputationVote> votes = db.getVotesForTarget(cache.discordId());

                // Filter votes older than consensus threshold
                List<ReputationVote> oldVotes = votes.stream()
                    .filter(v -> v.votedAt().isBefore(consensusThreshold))
                    .collect(Collectors.toList());

                if (oldVotes.isEmpty()) {
                    continue;
                }

                // Determine current consensus (majority vote direction)
                long positiveCount = votes.stream().filter(v -> v.voteValue() > 0).count();
                long negativeCount = votes.stream().filter(v -> v.voteValue() < 0).count();

                int consensusDirection;
                if (positiveCount > negativeCount * 1.5) {
                    consensusDirection = 1; // Positive consensus
                } else if (negativeCount > positiveCount * 1.5) {
                    consensusDirection = -1; // Negative consensus
                } else {
                    consensusDirection = 0; // No clear consensus
                }

                if (consensusDirection == 0) {
                    continue; // Skip if no clear consensus
                }

                // Check each old vote against consensus
                for (ReputationVote vote : oldVotes) {
                    String voterId = vote.voterDiscordId();

                    // Initialize consensus update for this voter if needed
                    voterUpdates.putIfAbsent(voterId, new ConsensusUpdate());

                    // Check if vote agrees with consensus
                    boolean agrees = (vote.voteValue() > 0 && consensusDirection > 0) ||
                                   (vote.voteValue() < 0 && consensusDirection < 0);

                    if (agrees) {
                        voterUpdates.get(voterId).agreements++;
                    } else {
                        voterUpdates.get(voterId).disagreements++;
                    }
                }
            }

            // Apply updates to voter stats
            int updated = 0;
            for (Map.Entry<String, ConsensusUpdate> entry : voterUpdates.entrySet()) {
                String voterId = entry.getKey();
                ConsensusUpdate update = entry.getValue();

                Optional<ReputationVoterStats> statsOpt = db.getVoterStats(voterId);
                if (statsOpt.isPresent()) {
                    ReputationVoterStats oldStats = statsOpt.get();

                    // Add new agreements/disagreements to existing counts
                    ReputationVoterStats newStats = new ReputationVoterStats(
                        oldStats.voterDiscordId(),
                        oldStats.accountCreatedAt(),
                        oldStats.totalVotesCast(),
                        oldStats.positiveVotesCast(),
                        oldStats.negativeVotesCast(),
                        oldStats.votesLast24h(),
                        oldStats.lastVoteAt(),
                        oldStats.consensusAgreements() + update.agreements,
                        oldStats.consensusDisagreements() + update.disagreements,
                        oldStats.credibilityScore()
                    );

                    db.updateVoterStats(newStats);
                    updated++;
                }
            }

            if (updated > 0) {
                logger.info("Consensus tracking complete: updated {} voter credibility records", updated);
            } else {
                logger.debug("Consensus tracking complete: no updates needed");
            }

        } catch (Exception e) {
            logger.error("Error during consensus tracking update", e);
        }
    }

    /**
     * Update percentile ranks for all users.
     */
    private void updateAllPercentiles() {
        try {
            logger.debug("Starting percentile rank update...");
            repManager.updatePercentileRanks();
            logger.debug("Percentile rank update complete");
        } catch (Exception e) {
            logger.error("Error during percentile rank update", e);
        }
    }

    /**
     * Reset votes_last_24h counter for all voters.
     * Should be called daily to prevent stale 24h counts.
     */
    private void resetDaily24hCounts() {
        try {
            logger.debug("Starting daily 24h vote count reset...");

            List<ReputationCache> allScores = db.getAllReputationScores();
            Instant oneDayAgo = Instant.now().minus(Duration.ofDays(1));
            int reset = 0;

            for (ReputationCache cache : allScores) {
                Optional<ReputationVoterStats> statsOpt = db.getVoterStats(cache.discordId());
                if (statsOpt.isPresent()) {
                    ReputationVoterStats oldStats = statsOpt.get();

                    // Count actual votes in last 24h
                    List<ReputationVote> recentVotes = db.getVotesInTimeframe(
                        cache.discordId(), oneDayAgo
                    );

                    if (recentVotes.size() != oldStats.votesLast24h()) {
                        // Update with accurate count
                        ReputationVoterStats newStats = new ReputationVoterStats(
                            oldStats.voterDiscordId(),
                            oldStats.accountCreatedAt(),
                            oldStats.totalVotesCast(),
                            oldStats.positiveVotesCast(),
                            oldStats.negativeVotesCast(),
                            recentVotes.size(),
                            oldStats.lastVoteAt(),
                            oldStats.consensusAgreements(),
                            oldStats.consensusDisagreements(),
                            oldStats.credibilityScore()
                        );

                        db.updateVoterStats(newStats);
                        reset++;
                    }
                }
            }

            logger.info("Daily 24h vote count reset complete: {} voters updated", reset);

        } catch (Exception e) {
            logger.error("Error during daily 24h vote count reset", e);
        }
    }

    /**
     * Shuts down the reputation scheduler.
     */
    public void shutdown() {
        logger.info("Shutting down reputation scheduler...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Helper class to track consensus updates for a voter.
     */
    private static class ConsensusUpdate {
        int agreements = 0;
        int disagreements = 0;
    }
}
