package com.confect1on.sentinel.db;

import java.time.Instant;

/**
 * Record representing a Terms of Service attestation.
 */
public record TosAttestationInfo(
    String discordId,
    String version,
    Instant agreedAt
) {
    /**
     * Checks if this attestation is for a specific version.
     * 
     * @param targetVersion The version to check against
     * @return true if this attestation matches the target version
     */
    public boolean isForVersion(String targetVersion) {
        return version != null && version.equals(targetVersion);
    }
    
    /**
     * Gets a formatted timestamp for Discord display.
     * 
     * @return Discord timestamp format string
     */
    public String getDiscordTimestamp() {
        return "<t:" + agreedAt.getEpochSecond() + ":R>";
    }
}