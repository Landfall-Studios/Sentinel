package com.confect1on.sentinel.config;

public class SentinelConfig {
    public MySQL mysql = new MySQL();
    public Discord discord = new Discord();
    public Tos tos = new Tos();
    public BypassServers bypassServers = new BypassServers();
    public Impersonation impersonation = new Impersonation();
    public Reputation reputation = new Reputation();

    public static class MySQL {
        public String host = "localhost";
        public int port = 3306;
        public String database = "sentinel";
        public String username = "sentinel_user";
        public String password = "change_me";
    }

    public static class Discord {
        public String token = "";
        public String linkedRole = ""; // Role ID to assign to linked players (optional)
        public String quarantineRole = ""; // Role ID that prevents login (optional)
        public String quarantineMessage = "Your account has been quarantined. Contact an administrator."; // Message shown to quarantined users
        public String[] staffRoles = new String[0]; // Role IDs that can use staff cmds (optional)
        public String tosAuditChannel = ""; // Channel ID to send ToS agreement confirmations (optional)
        public String moderationAuditChannel = ""; // Channel ID for moderation action logs (optional)
    }
    
    public static class Tos {
        public boolean enforcement = false; // Enable ToS enforcement
        public String version = "1.0.0"; // Current ToS version
        public String url = ""; // URL to full ToS document
        public String content = ""; // ToS content to display (supports multi-line)
        public boolean ipLogging = true; // Enable IP logging for login attempts
    }

    public static class BypassServers {
        public String[] servers = new String[0];
    }

    public static class Impersonation {
        public boolean enabled = false;
        public String[] allowedUsers = new String[0];
    }

    public static class Reputation {
        // Time factors
        public double timeDecayRate = 0.023;  // 50% weight after 30 days: e^(-0.023 × 30) ≈ 0.5
        public int voteCooldownDays = 7;      // Minimum days between voting same target

        // Credibility factors
        public int fullCredibilityDays = 30;  // Days until voter has full credibility
        public double spamDampenerFactor = 0.1;  // Spam dampening: 1.0 / (1 + votes_in_24h × 0.1)
        public double highRepMultiplierMax = 1.5;  // Max vote weight for high-rep voters
        public double lowRepMultiplierMin = 0.5;   // Min vote weight for low-rep voters

        // Anti-abuse weights
        public double reciprocalQuickWeight = 0.4;    // Weight if reciprocal vote within 1 hour
        public double reciprocalDelayedWeight = 0.75; // Weight if reciprocal vote within 1-7 days
        public double brigadingWeight = 0.3;          // Weight if 3+ same-sign votes in 10 minutes
        public double singleDirectionWeight = 0.7;    // Weight if only ever giving +/- votes

        // Comment quality weights
        public double noCommentWeight = 0.9;       // Weight if no comment
        public double shortCommentWeight = 1.0;    // Weight for short comments (10-50 chars)
        public double detailedCommentWeight = 1.3; // Weight for detailed comments (50+ chars)
        public double vagueCommentWeight = 0.7;    // Weight for vague complaints

        // Vague comment patterns (lowercase)
        public String[] vagueCommentPatterns = new String[]{
            "trash", "noob", "bad", "sucks", "terrible", "awful", "worst"
        };

        // Progressive reputation penalties/bonuses
        public double highPercentileThreshold = 80.0;  // Top percentile for penalty
        public double lowPercentileThreshold = 20.0;   // Bottom percentile for bonus
        public double highPercentileMinWeight = 0.5;   // Min weight for downvoting popular players
        public double lowPercentileMaxWeight = 1.5;    // Max weight for upvoting unpopular players

        // Small server scaling (unique voters → multiplier)
        public int smallServer3to5 = 3;
        public double smallServer3to5Multiplier = 1.1;
        public int smallServer6to10 = 6;
        public double smallServer6to10Multiplier = 1.2;
        public int smallServer11to15 = 11;
        public double smallServer11to15Multiplier = 1.3;
        public int smallServer16to20 = 16;
        public double smallServer16to20Multiplier = 1.4;
        public double smallServerMaxMultiplier = 1.5;  // Cap at 20+ unique voters

        // Consensus tracking
        public int consensusDays = 30;  // Days before checking consensus agreement

        // Cache settings
        public int cacheStaleMinutes = 60;  // Minutes before cache is considered stale
        public int scheduleRefreshMinutes = 60;  // Minutes between scheduled cache refreshes

        // Display settings
        public int displayRecentVotesCount = 10;  // Number of recent votes to show
    }
}
