package world.landfall.sentinel.db;

import java.time.Instant;

public record QuarantineInfo(
    String discordId,
    String reason,
    Instant expiresAt,    // null for permanent
    Instant createdAt,
    String createdBy      // Discord ID of staff who issued it
) {
    /**
     * Returns true if this quarantine is still active (not expired).
     */
    public boolean isActive() {
        return expiresAt == null || Instant.now().isBefore(expiresAt);
    }

    /**
     * Returns true if this is a permanent quarantine.
     */
    public boolean isPermanent() {
        return expiresAt == null;
    }

    /**
     * Gets the remaining duration in seconds, or -1 if permanent.
     */
    public long getRemainingSeconds() {
        if (isPermanent()) {
            return -1;
        }
        long remaining = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }

    /**
     * Formats the remaining time in a human-readable format.
     */
    public String getFormattedTimeRemaining() {
        if (isPermanent()) {
            return "Permanent";
        }

        long seconds = getRemainingSeconds();
        if (seconds <= 0) {
            return "Expired";
        }

        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }
}
