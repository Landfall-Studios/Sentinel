package world.landfall.sentinel.reputation;

import world.landfall.sentinel.config.SentinelConfig;
import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.DatabaseManager.ReputationVote;
import world.landfall.sentinel.db.DatabaseManager.ReputationCache;
import world.landfall.sentinel.db.DatabaseManager.ReputationVoterStats;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages reputation calculation with complex scoring formula.
 * Implements time decay, credibility factors, anti-abuse detection, and more.
 */
public class ReputationManager {

    private final DatabaseManager db;
    private final SentinelConfig.Reputation config;
    private final Logger logger;

    public ReputationManager(DatabaseManager db, SentinelConfig.Reputation config, Logger logger) {
        this.db = db;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Calculate or retrieve cached reputation score for a user.
     * Uses hybrid approach: returns cache if fresh, otherwise recalculates.
     *
     * @param discordId The user's Discord ID
     * @return The reputation score result
     */
    public ReputationResult getReputation(String discordId) {
        // Check cache first
        Optional<ReputationCache> cached = db.getReputationCache(discordId);
        if (cached.isPresent()) {
            ReputationCache cache = cached.get();
            long minutesSinceCalculation = Duration.between(cache.lastCalculated(), Instant.now()).toMinutes();

            if (minutesSinceCalculation < config.cacheStaleMinutes) {
                // Cache is fresh
                List<ReputationVote> recentVotes = db.getRecentVotes(discordId, config.displayRecentVotesCount);
                return new ReputationResult(cache, recentVotes, true);
            }
        }

        // Cache is stale or doesn't exist - recalculate
        return calculateReputation(discordId);
    }

    /**
     * Calculate reputation score for a user using the full formula.
     *
     * @param discordId The user's Discord ID
     * @return The calculated reputation result
     */
    public ReputationResult calculateReputation(String discordId) {
        List<ReputationVote> votes = db.getVotesForTarget(discordId);

        if (votes.isEmpty()) {
            // No votes yet - return neutral score
            ReputationCache cache = new ReputationCache(
                discordId, 0.0, 0, Instant.now(), 0, 50.0
            );
            db.updateReputationCache(cache);
            return new ReputationResult(cache, Collections.emptyList(), false);
        }

        // Get all scores for percentile calculation
        List<ReputationCache> allScores = db.getAllReputationScores();

        double totalScore = 0.0;
        Instant now = Instant.now();

        // Calculate weighted score for each vote
        for (ReputationVote vote : votes) {
            double weight = calculateVoteWeight(vote, discordId, votes, now, allScores);
            totalScore += vote.voteValue() * weight;
        }

        // Apply tanh normalization for display score (-100 to +100)
        int displayScore = (int) Math.round(Math.tanh(totalScore / 10.0) * 100);

        // Calculate percentile rank
        double percentile = calculatePercentileRank(displayScore, allScores);

        // Create and cache the result
        ReputationCache cache = new ReputationCache(
            discordId, totalScore, displayScore, now, votes.size(), percentile
        );
        db.updateReputationCache(cache);

        // Update percentile for this user in case it changed
        updatePercentileRanks();

        List<ReputationVote> recentVotes = votes.stream()
            .limit(config.displayRecentVotesCount)
            .collect(Collectors.toList());

        return new ReputationResult(cache, recentVotes, false);
    }

    /**
     * Calculate the weight for a single vote based on all factors.
     */
    private double calculateVoteWeight(ReputationVote vote, String targetId,
                                       List<ReputationVote> allTargetVotes, Instant now,
                                       List<ReputationCache> allScores) {
        double weight = 1.0;

        // 1. Time decay: e^(-0.023 * days_old)
        weight *= getTimeDecay(vote.votedAt(), now);

        // 2. Voter credibility (combines multiple factors)
        weight *= getVoterCredibility(vote.voterDiscordId(), vote.votedAt(), now);

        // 3. Comment quality
        weight *= getCommentQualityMultiplier(vote.comment());

        // 4. Anti-abuse patterns
        weight *= getAntiAbuseMultiplier(vote, now);

        // 5. Diversity penalty
        weight *= getDiversityMultiplier(vote, allTargetVotes);

        // 6. Progressive reputation penalty/bonus
        weight *= getProgressiveMultiplier(vote, targetId, allScores);

        // 7. Small server scaling
        weight *= getSmallServerScaling(allTargetVotes);

        return weight;
    }

    /**
     * Time decay: e^(-0.023 * days_old)
     * Votes lose ~50% weight after 30 days
     */
    private double getTimeDecay(Instant votedAt, Instant now) {
        long days = Duration.between(votedAt, now).toDays();
        return Math.exp(-config.timeDecayRate * days);
    }

    /**
     * Voter credibility combines: account age, vote diversity, spam dampener,
     * voter's own reputation, and consensus agreement.
     */
    private double getVoterCredibility(String voterDiscordId, Instant voteTime, Instant now) {
        double credibility = 1.0;

        // Get or create voter stats
        ReputationVoterStats stats = db.getVoterStats(voterDiscordId).orElse(null);
        if (stats == null) {
            // New voter - use defaults
            stats = new ReputationVoterStats(
                voterDiscordId, voteTime, 0, 0, 0, 0, null, 0, 0, 1.0
            );
        }

        // Account age: min(1.0, account_days / 30)
        long accountDays = Duration.between(stats.accountCreatedAt(), now).toDays();
        double accountAgeFactor = Math.min(1.0, (double) accountDays / config.fullCredibilityDays);
        credibility *= accountAgeFactor;

        // Vote diversity: penalty if only using positive OR only negative
        if (stats.totalVotesCast() > 5) { // Only apply after 5 votes
            int positive = stats.positiveVotesCast();
            int negative = stats.negativeVotesCast();
            int total = stats.totalVotesCast();

            double positiveRatio = (double) positive / total;
            double negativeRatio = (double) negative / total;

            // If heavily skewed to one direction, apply penalty gradually
            if (positiveRatio > 0.95 || negativeRatio > 0.95) {
                double skewness = Math.max(positiveRatio, negativeRatio);
                // Gradually reduce weight from 1.0 to 0.7 as skewness goes from 0.95 to 1.0
                double diversityPenalty = 1.0 - (skewness - 0.95) * 6.0; // 6.0 = 0.3 / 0.05
                diversityPenalty = Math.max(config.singleDirectionWeight, diversityPenalty);
                credibility *= diversityPenalty;
            }
        }

        // Spam dampener: 1.0 / (1 + votes_in_24h * 0.1)
        double spamDampener = 1.0 / (1.0 + stats.votesLast24h() * config.spamDampenerFactor);
        credibility *= spamDampener;

        // Voter's own reputation weight
        Optional<ReputationCache> voterRep = db.getReputationCache(voterDiscordId);
        if (voterRep.isPresent()) {
            int voterScore = voterRep.get().displayScore();
            double repMultiplier;
            if (voterScore >= 50) {
                // High rep: scale from 1.0 to 1.5 as score goes from 50 to 100
                repMultiplier = 1.0 + (voterScore - 50) / 100.0 * 0.5;
            } else if (voterScore <= -50) {
                // Low rep: scale from 1.0 to 0.5 as score goes from -50 to -100
                repMultiplier = 1.0 - (Math.abs(voterScore) - 50) / 100.0 * 0.5;
            } else {
                // Near neutral: 1.0
                repMultiplier = 1.0;
            }
            credibility *= repMultiplier;
        }

        // Consensus agreement rate
        int totalConsensusChecks = stats.consensusAgreements() + stats.consensusDisagreements();
        if (totalConsensusChecks >= 10) { // Only apply after 10 consensus checks
            double agreementRate = (double) stats.consensusAgreements() / totalConsensusChecks;
            double consensusMultiplier;
            if (agreementRate >= 0.70) {
                consensusMultiplier = 1.0;
            } else if (agreementRate >= 0.50) {
                consensusMultiplier = 0.9;
            } else if (agreementRate >= 0.30) {
                consensusMultiplier = 0.7;
            } else {
                consensusMultiplier = 0.5;
            }
            credibility *= consensusMultiplier;
        }

        return credibility;
    }

    /**
     * Comment quality multiplier based on content and length.
     */
    private double getCommentQualityMultiplier(String comment) {
        if (comment == null || comment.isEmpty()) {
            return config.noCommentWeight;
        }

        int length = comment.length();
        String lowerComment = comment.toLowerCase();

        // Check for vague complaints
        for (String pattern : config.vagueCommentPatterns) {
            if (lowerComment.contains(pattern)) {
                return config.vagueCommentWeight;
            }
        }

        // Length-based quality
        if (length >= 50) {
            return config.detailedCommentWeight;
        } else if (length >= 10) {
            return config.shortCommentWeight;
        } else {
            return config.noCommentWeight;
        }
    }

    /**
     * Anti-abuse pattern detection: reciprocal voting, brigading.
     */
    private double getAntiAbuseMultiplier(ReputationVote vote, Instant now) {
        double multiplier = 1.0;

        // Check for reciprocal voting (A votes B, B votes A)
        Optional<ReputationVote> reciprocal = db.getVoteByVoterAndTarget(
            vote.targetDiscordId(), vote.voterDiscordId()
        );

        if (reciprocal.isPresent()) {
            ReputationVote recip = reciprocal.get();
            // Only penalize if both votes are same sign
            if (recip.voteValue() == vote.voteValue()) {
                Duration timeBetween = Duration.between(vote.votedAt(), recip.votedAt()).abs();
                if (timeBetween.toHours() < 1) {
                    multiplier *= config.reciprocalQuickWeight;
                } else if (timeBetween.toDays() < 7) {
                    multiplier *= config.reciprocalDelayedWeight;
                }
            }
        }

        // Check for vote brigading (3+ same-sign votes on target within 10 minutes)
        Instant tenMinutesAgo = vote.votedAt().minus(Duration.ofMinutes(10));
        Instant tenMinutesAfter = vote.votedAt().plus(Duration.ofMinutes(10));

        List<ReputationVote> nearbyVotes = db.getVotesForTarget(vote.targetDiscordId()).stream()
            .filter(v -> v.votedAt().isAfter(tenMinutesAgo) && v.votedAt().isBefore(tenMinutesAfter))
            .filter(v -> v.voteValue() == vote.voteValue())
            .toList();

        if (nearbyVotes.size() >= 3) {
            multiplier *= config.brigadingWeight;
        }

        return multiplier;
    }

    /**
     * Diversity penalty: if few unique voters, reduce weight.
     */
    private double getDiversityMultiplier(ReputationVote vote, List<ReputationVote> allTargetVotes) {
        // Count unique voters for positive and negative votes
        Set<String> positiveVoters = new HashSet<>();
        Set<String> negativeVoters = new HashSet<>();
        int positiveCount = 0;
        int negativeCount = 0;

        for (ReputationVote v : allTargetVotes) {
            if (v.voteValue() > 0) {
                positiveVoters.add(v.voterDiscordId());
                positiveCount++;
            } else {
                negativeVoters.add(v.voterDiscordId());
                negativeCount++;
            }
        }

        if (positiveCount == 0 || negativeCount == 0) {
            return 1.0; // No penalty if only one type of vote
        }

        double positiveDiversity = (double) positiveVoters.size() / positiveCount;
        double negativeDiversity = (double) negativeVoters.size() / negativeCount;

        // Apply penalty to the side with lower diversity
        if (vote.voteValue() > 0 && positiveDiversity < negativeDiversity) {
            return Math.pow(positiveDiversity / negativeDiversity, 0.5);
        } else if (vote.voteValue() < 0 && negativeDiversity < positiveDiversity) {
            return Math.pow(negativeDiversity / positiveDiversity, 0.5);
        }

        return 1.0;
    }

    /**
     * Progressive reputation penalties/bonuses based on target's percentile.
     */
    private double getProgressiveMultiplier(ReputationVote vote, String targetId,
                                           List<ReputationCache> allScores) {
        Optional<ReputationCache> targetCache = db.getReputationCache(targetId);
        if (targetCache.isEmpty()) {
            return 1.0; // No adjustment for first vote
        }

        double percentile = targetCache.get().percentileRank();

        // Negative vote on popular player (top 80-100th percentile): reduce weight
        if (vote.voteValue() < 0 && percentile >= config.highPercentileThreshold) {
            double progress = (percentile - config.highPercentileThreshold) /
                            (100.0 - config.highPercentileThreshold);
            // Scale from 1.0 to 0.5
            return 1.0 - progress * (1.0 - config.highPercentileMinWeight);
        }

        // Positive vote on unpopular player (bottom 0-20th percentile): increase weight
        if (vote.voteValue() > 0 && percentile <= config.lowPercentileThreshold) {
            double progress = (config.lowPercentileThreshold - percentile) /
                            config.lowPercentileThreshold;
            // Scale from 1.0 to 1.5
            return 1.0 + progress * (config.lowPercentileMaxWeight - 1.0);
        }

        return 1.0;
    }

    /**
     * Small server scaling: boost scores when few unique voters.
     */
    private double getSmallServerScaling(List<ReputationVote> allTargetVotes) {
        Set<String> uniqueVoters = allTargetVotes.stream()
            .map(ReputationVote::voterDiscordId)
            .collect(Collectors.toSet());

        int count = uniqueVoters.size();

        if (count >= 20) {
            return config.smallServerMaxMultiplier;
        } else if (count >= config.smallServer16to20) {
            return config.smallServer16to20Multiplier;
        } else if (count >= config.smallServer11to15) {
            return config.smallServer11to15Multiplier;
        } else if (count >= config.smallServer6to10) {
            return config.smallServer6to10Multiplier;
        } else if (count >= config.smallServer3to5) {
            return config.smallServer3to5Multiplier;
        }

        return 1.0; // No scaling for <3 voters
    }

    /**
     * Calculate percentile rank for a score.
     */
    private double calculatePercentileRank(int displayScore, List<ReputationCache> allScores) {
        if (allScores.isEmpty()) {
            return 50.0; // Median for first user
        }

        long belowCount = allScores.stream()
            .filter(s -> s.displayScore() < displayScore)
            .count();

        return (double) belowCount / allScores.size() * 100.0;
    }

    /**
     * Update percentile ranks for all users.
     * Called after recalculating a score.
     */
    public void updatePercentileRanks() {
        List<ReputationCache> allScores = db.getAllReputationScores();

        for (ReputationCache cache : allScores) {
            double percentile = calculatePercentileRank(cache.displayScore(), allScores);

            if (Math.abs(percentile - cache.percentileRank()) > 0.1) {
                // Update if changed significantly
                ReputationCache updated = new ReputationCache(
                    cache.discordId(), cache.totalScore(), cache.displayScore(),
                    cache.lastCalculated(), cache.totalVotesReceived(), percentile
                );
                db.updateReputationCache(updated);
            }
        }
    }

    /**
     * Submit a reputation vote with validation and cooldown checking.
     *
     * @param voterDiscordId The voter's Discord ID
     * @param targetDiscordId The target's Discord ID
     * @param voteValue +1 or -1
     * @param comment Optional comment
     * @return VoteResult with success status and message
     */
    public VoteResult submitVote(String voterDiscordId, String targetDiscordId,
                                 int voteValue, String comment) {
        // Prevent self-voting
        if (voterDiscordId.equals(targetDiscordId)) {
            return new VoteResult(false, "You cannot vote on yourself.");
        }

        // Check vote cooldown
        Optional<ReputationVote> existing = db.getVoteByVoterAndTarget(voterDiscordId, targetDiscordId);
        if (existing.isPresent()) {
            ReputationVote existingVote = existing.get();
            long daysSinceVote = Duration.between(existingVote.votedAt(), Instant.now()).toDays();

            if (daysSinceVote < config.voteCooldownDays) {
                long daysRemaining = config.voteCooldownDays - daysSinceVote;
                return new VoteResult(false,
                    String.format("You must wait %d more day(s) before voting on this user again.",
                    daysRemaining));
            }
        }

        // Validate vote value
        if (voteValue != 1 && voteValue != -1) {
            return new VoteResult(false, "Invalid vote value. Must be +1 or -1.");
        }

        // Submit the vote
        boolean success = db.addOrUpdateVote(voterDiscordId, targetDiscordId, voteValue, comment);
        if (!success) {
            return new VoteResult(false, "Failed to submit vote. Please try again.");
        }

        // Update voter statistics
        updateVoterStatistics(voterDiscordId, voteValue);

        // Invalidate target's cache by recalculating
        calculateReputation(targetDiscordId);

        return new VoteResult(true, "Vote submitted successfully!");
    }

    /**
     * Update voter statistics after submitting a vote.
     */
    private void updateVoterStatistics(String voterDiscordId, int voteValue) {
        Optional<ReputationVoterStats> existing = db.getVoterStats(voterDiscordId);
        Instant now = Instant.now();

        ReputationVoterStats stats;
        if (existing.isPresent()) {
            ReputationVoterStats old = existing.get();

            // Count votes in last 24 hours
            List<ReputationVote> recent = db.getVotesInTimeframe(
                voterDiscordId, now.minus(Duration.ofDays(1))
            );

            stats = new ReputationVoterStats(
                voterDiscordId,
                old.accountCreatedAt(),
                old.totalVotesCast() + 1,
                old.positiveVotesCast() + (voteValue > 0 ? 1 : 0),
                old.negativeVotesCast() + (voteValue < 0 ? 1 : 0),
                recent.size() + 1, // +1 for the current vote
                now,
                old.consensusAgreements(),
                old.consensusDisagreements(),
                old.credibilityScore()
            );
        } else {
            // New voter
            stats = new ReputationVoterStats(
                voterDiscordId,
                now,
                1,
                voteValue > 0 ? 1 : 0,
                voteValue < 0 ? 1 : 0,
                1,
                now,
                0,
                0,
                1.0
            );
        }

        db.updateVoterStats(stats);
    }

    /**
     * Result of a reputation calculation.
     */
    public record ReputationResult(
        ReputationCache cache,
        List<ReputationVote> recentVotes,
        boolean fromCache
    ) {}

    /**
     * Result of a vote submission.
     */
    public record VoteResult(
        boolean success,
        String message
    ) {}
}
