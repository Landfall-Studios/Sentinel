package world.landfall.sentinel.db;

import world.landfall.sentinel.config.SentinelConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import world.landfall.sentinel.db.QuarantineInfo;
import java.sql.Types;

public class DatabaseManager {

    private final HikariDataSource dataSource;
    private final Logger logger;

    public DatabaseManager(SentinelConfig.MySQL config, Logger logger) {
        this.logger = logger;

        HikariConfig hikari = new HikariConfig();
        hikari.setDriverClassName(config.driverClassName);
        hikari.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s", config.host, config.port, config.database));
        hikari.setUsername(config.username);
        hikari.setPassword(config.password);
        hikari.setMaximumPoolSize(5);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(5000);
        hikari.setIdleTimeout(60000);
        hikari.setMaxLifetime(300000);
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        try {
            this.dataSource = new HikariDataSource(hikari);
            // Test connection right away
            try (Connection ignored = dataSource.getConnection()) {
                logger.info("✅ Sentinel successfully connected to MySQL at {}:{}",
                        config.host, config.port);
            }
        } catch (Exception e) {
            logger.error("❌ Failed to connect to MySQL at {}:{} — shutting down Sentinel",
                    config.host, config.port, e);
            throw new RuntimeException("Database connection failed", e);
        }

        initTables();
    }

    private void initTables() {
        // linked_accounts: UUID PK, discord_id UNIQUE (and indexed), username indexed
        String createLinked = """
            CREATE TABLE IF NOT EXISTS linked_accounts (
              uuid        VARCHAR(36)  PRIMARY KEY,
              discord_id  VARCHAR(32)  NOT NULL UNIQUE,
              username    VARCHAR(16),
              INDEX idx_username (username)
            );
            """;

        // pending_links: small table, no extra indexes
        String createPending = """
            CREATE TABLE IF NOT EXISTS pending_links (
              uuid        VARCHAR(36)  PRIMARY KEY,
              code        VARCHAR(16)  NOT NULL,
              created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            """;

        // quarantines: timed quarantine records
        String createQuarantines = """
            CREATE TABLE IF NOT EXISTS quarantines (
              discord_id  VARCHAR(32)  PRIMARY KEY,
              reason      TEXT         NOT NULL,
              expires_at  TIMESTAMP    NULL,
              created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              created_by  VARCHAR(32)  NOT NULL,
              INDEX idx_expires_at (expires_at)
            );
            """;

        // tos_attestations: tracks ToS agreements
        String createTosAttestations = """
            CREATE TABLE IF NOT EXISTS tos_attestations (
              discord_id  VARCHAR(32)  PRIMARY KEY,
              version     VARCHAR(16)  NOT NULL,
              agreed_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              INDEX idx_version (version)
            );
            """;

        // tos_versions: stores ToS version history
        String createTosVersions = """
            CREATE TABLE IF NOT EXISTS tos_versions (
              version     VARCHAR(16)  PRIMARY KEY,
              content     TEXT         NOT NULL,
              created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              active      BOOLEAN      DEFAULT TRUE
            );
            """;

        // login_ips: tracks IP addresses for moderation and compliance
        String createLoginIps = """
            CREATE TABLE IF NOT EXISTS login_ips (
              id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
              uuid         VARCHAR(36)  NOT NULL,
              discord_id   VARCHAR(32),
              ip_address   VARCHAR(45)  NOT NULL,
              login_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              login_allowed BOOLEAN     NOT NULL,
              deny_reason  VARCHAR(255),
              INDEX idx_uuid (uuid),
              INDEX idx_discord_id (discord_id),
              INDEX idx_ip_address (ip_address)
            );
            """;

        // moderation_actions: tracks notes, warnings, bans, and unbans
        String createModerationActions = """
            CREATE TABLE IF NOT EXISTS moderation_actions (
              id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
              discord_id    VARCHAR(32)  NOT NULL,
              minecraft_uuid VARCHAR(36),
              action_type   ENUM('NOTE', 'WARN', 'BAN', 'UNBAN') NOT NULL,
              reason        TEXT         NOT NULL,
              issued_by     VARCHAR(32)  NOT NULL,
              issued_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              INDEX idx_discord_id (discord_id),
              INDEX idx_action_type (action_type),
              INDEX idx_issued_at (issued_at)
            );
            """;

        // reputation_votes: tracks individual reputation votes between players
        String createReputationVotes = """
            CREATE TABLE IF NOT EXISTS reputation_votes (
              id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
              voter_discord_id  VARCHAR(32)  NOT NULL,
              target_discord_id VARCHAR(32)  NOT NULL,
              vote_value        TINYINT      NOT NULL,
              comment           TEXT,
              comment_length    INT          DEFAULT 0,
              voted_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY unique_vote (voter_discord_id, target_discord_id),
              INDEX idx_target (target_discord_id),
              INDEX idx_voter (voter_discord_id),
              INDEX idx_voted_at (voted_at)
            );
            """;

        // reputation_cache: stores calculated reputation scores
        String createReputationCache = """
            CREATE TABLE IF NOT EXISTS reputation_cache (
              discord_id        VARCHAR(32)  PRIMARY KEY,
              total_score       DECIMAL(10,4) DEFAULT 0,
              display_score     INT           DEFAULT 0,
              last_calculated   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              total_votes_received INT        DEFAULT 0,
              percentile_rank   DECIMAL(5,2)  DEFAULT 50.0,
              INDEX idx_display_score (display_score),
              INDEX idx_percentile (percentile_rank)
            );
            """;

        // reputation_voter_stats: tracks per-voter credibility metrics
        String createReputationVoterStats = """
            CREATE TABLE IF NOT EXISTS reputation_voter_stats (
              voter_discord_id      VARCHAR(32)  PRIMARY KEY,
              account_created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              total_votes_cast      INT          DEFAULT 0,
              positive_votes_cast   INT          DEFAULT 0,
              negative_votes_cast   INT          DEFAULT 0,
              votes_last_24h        INT          DEFAULT 0,
              last_vote_at          TIMESTAMP    NULL,
              consensus_agreements  INT          DEFAULT 0,
              consensus_disagreements INT        DEFAULT 0,
              credibility_score     DECIMAL(5,4) DEFAULT 1.0
            );
            """;

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(createLinked);
            st.executeUpdate(createPending);
            st.executeUpdate(createQuarantines);
            st.executeUpdate(createTosAttestations);
            st.executeUpdate(createTosVersions);
            st.executeUpdate(createLoginIps);
            st.executeUpdate(createModerationActions);
            st.executeUpdate(createReputationVotes);
            st.executeUpdate(createReputationCache);
            st.executeUpdate(createReputationVoterStats);

            // Update existing moderation_actions table to support NOTE and UNBAN
            try {
                String alterModerationActions = "ALTER TABLE moderation_actions MODIFY COLUMN action_type ENUM('NOTE', 'WARN', 'BAN', 'UNBAN') NOT NULL";
                st.executeUpdate(alterModerationActions);
                logger.info("Updated moderation_actions table to support NOTE and UNBAN action types.");
            } catch (SQLException e) {
                // Table might already have the correct schema or might not exist yet
                logger.debug("Could not alter moderation_actions table (may already be correct): {}", e.getMessage());
            }

            // Add duration column to moderation_actions table for tracking ban durations
            try {
                String addDurationColumn = "ALTER TABLE moderation_actions ADD COLUMN IF NOT EXISTS duration VARCHAR(100)";
                st.executeUpdate(addDurationColumn);
                logger.info("Added duration column to moderation_actions table.");
            } catch (SQLException e) {
                // Column might already exist
                logger.debug("Could not add duration column to moderation_actions table (may already exist): {}", e.getMessage());
            }
        } catch (SQLException e) {
            logger.error("Failed to init DB tables", e);
        }
    }

    public boolean isLinked(UUID uuid) {
        String query = "SELECT 1 FROM linked_accounts WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Failed to check linked status for UUID: {}", uuid, e);
            return false;
        }
    }

    /**
     * Cache or update the player's last-seen username.
     */
    public void updateUsername(UUID uuid, String username) {
        String sql = "UPDATE linked_accounts SET username = ? WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Could not update username for {}", uuid, e);
        }
    }

    /**
     * Inserts or rotates the pending link code for this UUID.
     */
    public void savePendingCode(UUID uuid, String code) {
        String sql = """
            INSERT INTO pending_links (uuid, code, created_at)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE
              code = VALUES(code),
              created_at = VALUES(created_at)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, code);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to store pending link for {}", uuid, e);
        }
    }

    /**
     * Atomically claims a pending link code:
     *  - looks up the UUID by code
     *  - deletes that pending row
     *  - returns the claimed UUID (or null if none)
     */
    public UUID claimPending(String code) {
        String select = "SELECT uuid FROM pending_links WHERE code = ?";
        String delete = "DELETE FROM pending_links WHERE code = ?";
        try (Connection conn = dataSource.getConnection()) {
            // lookup
            try (PreparedStatement ps = conn.prepareStatement(select)) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    // delete
                    try (PreparedStatement del = conn.prepareStatement(delete)) {
                        del.setString(1, code);
                        del.executeUpdate();
                    }
                    return uuid;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to claim pending code {}", code, e);
            return null;
        }
    }

    /**
     * Attempts to insert into linked_accounts.
     * Returns false if the Discord ID is already linked, true otherwise.
     */
    public boolean addLink(UUID uuid, String discordId) {
        String check  = "SELECT 1 FROM linked_accounts WHERE discord_id = ?";
        String insert = "INSERT INTO linked_accounts (uuid, discord_id) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection()) {
            // ensure this Discord ID isn't already linked
            try (PreparedStatement ps = conn.prepareStatement(check)) {
                ps.setString(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            // insert new link
            try (PreparedStatement ps2 = conn.prepareStatement(insert)) {
                ps2.setString(1, uuid.toString());
                ps2.setString(2, discordId);
                ps2.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            logger.error("Failed to add link {} ↔ {}", uuid, discordId, e);
            return false;
        }
    }

    public Optional<LinkInfo> findByDiscordId(String discordId) {
        String sql = "SELECT uuid, discord_id, username FROM linked_accounts WHERE discord_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new LinkInfo(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("discord_id"),
                        rs.getString("username")
                ));
            }
        } catch (SQLException e) {
            logger.error("Error looking up by Discord ID {}", discordId, e);
            return Optional.empty();
        }
    }

    public Optional<LinkInfo> findByUsername(String username) {
        String sql = "SELECT uuid, discord_id, username FROM linked_accounts WHERE username = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new LinkInfo(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("discord_id"),
                        rs.getString("username")
                ));
            }
        } catch (SQLException e) {
            logger.error("Error looking up by username {}", username, e);
            return Optional.empty();
        }
    }

    /**
     * Gets all linked accounts for role synchronization.
     * Returns a list of all Discord IDs that should have the linked role.
     */
    public List<String> getAllLinkedDiscordIds() {
        String sql = "SELECT discord_id FROM linked_accounts";
        List<String> discordIds = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                discordIds.add(rs.getString("discord_id"));
            }
        } catch (SQLException e) {
            logger.error("Error getting all linked Discord IDs", e);
        }
        return discordIds;
    }

    /**
     * Removes a linked account by Discord ID.
     * Used when a user leaves the Discord server.
     *
     * Note: Links are NOT removed if the user has ANY moderation history (warnings, bans, etc.).
     * This prevents punishment evasion by leaving Discord and relinking with a new account.
     */
    public boolean removeLinkByDiscordId(String discordId) {
        // Check if user has any moderation history - if so, don't unlink
        List<ModerationAction> history = getModerationHistory(discordId);
        if (!history.isEmpty()) {
            logger.info("🚫 Not unlinking {} - moderation history exists ({} actions)", discordId, history.size());
            return false;
        }

        String sql = "DELETE FROM linked_accounts WHERE discord_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);
            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            logger.error("Error removing link for Discord ID {}", discordId, e);
            return false;
        }
    }

    /**
     * Gets the Discord ID for a linked UUID.
     * Returns null if the UUID is not linked.
     */
    public String getDiscordId(UUID uuid) {
        String sql = "SELECT discord_id FROM linked_accounts WHERE uuid = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("discord_id");
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting Discord ID for UUID {}", uuid, e);
        }
        return null;
    }

    /**
     * Adds or updates a quarantine record.
     *
     * @param discordId The Discord ID to quarantine
     * @param reason The reason for the quarantine
     * @param expiresAt When the quarantine expires (null for permanent)
     * @param createdBy Discord ID of who created the quarantine
     * @return true if successful
     */
    public boolean addQuarantine(String discordId, String reason, Instant expiresAt, String createdBy) {
        String sql = """
            INSERT INTO quarantines (discord_id, reason, expires_at, created_at, created_by)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              reason = VALUES(reason),
              expires_at = VALUES(expires_at),
              created_at = VALUES(created_at),
              created_by = VALUES(created_by)
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);
            ps.setString(2, reason);
            if (expiresAt != null) {
                ps.setTimestamp(3, Timestamp.from(expiresAt));
            } else {
                ps.setNull(3, Types.TIMESTAMP);
            }
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setString(5, createdBy);

            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            logger.error("Error adding quarantine for Discord ID {}", discordId, e);
            return false;
        }
    }

    /**
     * Removes a quarantine record.
     *
     * @param discordId The Discord ID to remove quarantine from
     * @return true if a record was removed
     */
    public boolean removeQuarantine(String discordId) {
        String sql = "DELETE FROM quarantines WHERE discord_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);
            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            logger.error("Error removing quarantine for Discord ID {}", discordId, e);
            return false;
        }
    }

    /**
     * Gets an active quarantine record for a Discord ID.
     *
     * @param discordId The Discord ID to check
     * @return QuarantineInfo if quarantined and active, null otherwise
     */
    public Optional<QuarantineInfo> getActiveQuarantine(String discordId) {
        String sql = "SELECT discord_id, reason, expires_at, created_at, created_by FROM quarantines WHERE discord_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                Timestamp expiresAtTs = rs.getTimestamp("expires_at");
                Instant expiresAt = expiresAtTs != null ? expiresAtTs.toInstant() : null;

                QuarantineInfo info = new QuarantineInfo(
                    rs.getString("discord_id"),
                    rs.getString("reason"),
                    expiresAt,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("created_by")
                );

                // Return only if still active
                return info.isActive() ? Optional.of(info) : Optional.empty();
            }
        } catch (SQLException e) {
            logger.error("Error getting quarantine for Discord ID {}", discordId, e);
            return Optional.empty();
        }
    }

    /**
     * Gets all active quarantine records.
     * Used for administrative purposes.
     *
     * @return List of active quarantine records
     */
    public List<QuarantineInfo> getAllActiveQuarantines() {
        String sql = "SELECT discord_id, reason, expires_at, created_at, created_by FROM quarantines";
        List<QuarantineInfo> quarantines = new ArrayList<>();

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Timestamp expiresAtTs = rs.getTimestamp("expires_at");
                Instant expiresAt = expiresAtTs != null ? expiresAtTs.toInstant() : null;

                QuarantineInfo info = new QuarantineInfo(
                    rs.getString("discord_id"),
                    rs.getString("reason"),
                    expiresAt,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("created_by")
                );

                // Only include active quarantines
                if (info.isActive()) {
                    quarantines.add(info);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting all active quarantines", e);
        }

        return quarantines;
    }

    /**
     * Gets all expired quarantines.
     *
     * @return List of expired quarantines
     */
    public List<QuarantineInfo> getExpiredQuarantines() {
        String sql = """
            SELECT discord_id, reason, expires_at, created_at, created_by
            FROM quarantines
            WHERE expires_at IS NOT NULL AND expires_at <= ?
            """;

        List<QuarantineInfo> expired = new ArrayList<>();

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    expired.add(new QuarantineInfo(
                        rs.getString("discord_id"),
                        rs.getString("reason"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("created_by")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting expired quarantines", e);
        }

        return expired;
    }

    /**
     * Removes expired quarantine records from the database.
     */
    public void cleanupExpiredQuarantines() {
        String sql = "DELETE FROM quarantines WHERE expires_at IS NOT NULL AND expires_at <= ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                logger.debug("Cleaned up {} expired quarantine records", deleted);
            }
        } catch (SQLException e) {
            logger.error("Error cleaning up expired quarantines", e);
        }
    }

    /**
     * Adds or updates a ToS attestation.
     *
     * @param discordId The Discord ID attesting to ToS
     * @param version The ToS version being agreed to
     * @return true if successful
     */
    public boolean addTosAttestation(String discordId, String version) {
        String sql = """
            INSERT INTO tos_attestations (discord_id, version, agreed_at)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE
              version = VALUES(version),
              agreed_at = VALUES(agreed_at)
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);
            ps.setString(2, version);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));

            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            logger.error("Error adding ToS attestation for Discord ID {}", discordId, e);
            return false;
        }
    }

    /**
     * Gets the ToS version a user has agreed to.
     *
     * @param discordId The Discord ID to check
     * @return The version agreed to, or null if none
     */
    public String getTosAttestation(String discordId) {
        String sql = "SELECT version FROM tos_attestations WHERE discord_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("version");
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting ToS attestation for Discord ID {}", discordId, e);
        }
        return null;
    }

    /**
     * Adds or updates a ToS version.
     *
     * @param version The version identifier
     * @param content The full ToS content
     * @return true if successful
     */
    public boolean addTosVersion(String version, String content) {
        // First, deactivate all other versions
        String deactivateSql = "UPDATE tos_versions SET active = FALSE WHERE active = TRUE";
        String insertSql = """
            INSERT INTO tos_versions (version, content, created_at, active)
            VALUES (?, ?, ?, TRUE)
            ON DUPLICATE KEY UPDATE
              content = VALUES(content),
              active = TRUE
            """;

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Deactivate old versions
                try (PreparedStatement ps = c.prepareStatement(deactivateSql)) {
                    ps.executeUpdate();
                }

                // Insert/update new version
                try (PreparedStatement ps = c.prepareStatement(insertSql)) {
                    ps.setString(1, version);
                    ps.setString(2, content);
                    ps.setTimestamp(3, Timestamp.from(Instant.now()));
                    ps.executeUpdate();
                }

                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Error adding ToS version {}", version, e);
            return false;
        }
    }

    /**
     * Gets the active ToS version content.
     *
     * @return The active ToS content, or null if none
     */
    public String getActiveTosContent() {
        String sql = "SELECT content FROM tos_versions WHERE active = TRUE LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString("content");
            }
        } catch (SQLException e) {
            logger.error("Error getting active ToS content", e);
        }
        return null;
    }

    /**
     * Gets the ToS content for a specific version.
     *
     * @param version The version to get content for
     * @return The ToS content for that version, or null if not found
     */
    public String getTosVersionContent(String version) {
        String sql = "SELECT content FROM tos_versions WHERE version = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("content");
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting ToS content for version {}", version, e);
        }
        return null;
    }

    /**
     * Logs a login attempt with IP address.
     *
     * @param uuid The player UUID
     * @param discordId The Discord ID (nullable)
     * @param ipAddress The IP address
     * @param allowed Whether login was allowed
     * @param denyReason The reason for denial (nullable)
     * @return true if successful
     */
    public boolean logLoginIp(UUID uuid, String discordId, String ipAddress, boolean allowed, String denyReason) {
        String sql = """
            INSERT INTO login_ips (uuid, discord_id, ip_address, login_time, login_allowed, deny_reason)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            if (discordId != null) {
                ps.setString(2, discordId);
            } else {
                ps.setNull(2, Types.VARCHAR);
            }
            ps.setString(3, ipAddress);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setBoolean(5, allowed);
            if (denyReason != null) {
                ps.setString(6, denyReason);
            } else {
                ps.setNull(6, Types.VARCHAR);
            }

            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            logger.error("Error logging login IP for UUID {}", uuid, e);
            return false;
        }
    }

    /**
     * Gets recent login IPs for a player.
     *
     * @param uuid The player UUID
     * @param limit Maximum number of records to return
     * @return List of IP addresses with timestamps
     */
    public List<LoginIpInfo> getRecentLoginIps(UUID uuid, int limit) {
        String sql = """
            SELECT ip_address, login_time, login_allowed, deny_reason
            FROM login_ips
            WHERE uuid = ?
            ORDER BY login_time DESC
            LIMIT ?
            """;
        List<LoginIpInfo> ips = new ArrayList<>();

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ips.add(new LoginIpInfo(
                        rs.getString("ip_address"),
                        rs.getTimestamp("login_time").toInstant(),
                        rs.getBoolean("login_allowed"),
                        rs.getString("deny_reason")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting login IPs for UUID {}", uuid, e);
        }

        return ips;
    }

    // ==================== REPUTATION SYSTEM METHODS ====================

    /**
     * Adds or updates a reputation vote.
     * Uses ON DUPLICATE KEY UPDATE to replace existing votes from the same voter.
     *
     * @param voterDiscordId The Discord ID of the voter
     * @param targetDiscordId The Discord ID of the target
     * @param voteValue +1 or -1
     * @param comment Optional comment (nullable)
     * @return true if successful
     */
    public boolean addOrUpdateVote(String voterDiscordId, String targetDiscordId, int voteValue, String comment) {
        String sql = """
            INSERT INTO reputation_votes (voter_discord_id, target_discord_id, vote_value, comment, comment_length, voted_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              vote_value = VALUES(vote_value),
              comment = VALUES(comment),
              comment_length = VALUES(comment_length),
              voted_at = VALUES(voted_at)
            """;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterDiscordId);
            ps.setString(2, targetDiscordId);
            ps.setInt(3, voteValue);

            if (comment != null && !comment.isEmpty()) {
                ps.setString(4, comment);
                ps.setInt(5, comment.length());
            } else {
                ps.setNull(4, Types.VARCHAR);
                ps.setInt(5, 0);
            }

            ps.setTimestamp(6, Timestamp.from(Instant.now()));

            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            logger.error("Error adding/updating vote from {} to {}", voterDiscordId, targetDiscordId, e);
            return false;
        }
    }

    /**
     * Gets all votes for a target user.
     *
     * @param targetDiscordId The Discord ID of the target
     * @return List of votes
     */
    public List<ReputationVote> getVotesForTarget(String targetDiscordId) {
        String sql = """
            SELECT id, voter_discord_id, target_discord_id, vote_value, comment, comment_length, voted_at
            FROM reputation_votes
            WHERE target_discord_id = ?
            ORDER BY voted_at DESC
            """;

        List<ReputationVote> votes = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, targetDiscordId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    votes.add(new ReputationVote(
                        rs.getLong("id"),
                        rs.getString("voter_discord_id"),
                        rs.getString("target_discord_id"),
                        rs.getInt("vote_value"),
                        rs.getString("comment"),
                        rs.getInt("comment_length"),
                        rs.getTimestamp("voted_at").toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting votes for target {}", targetDiscordId, e);
        }

        return votes;
    }

    /**
     * Gets a specific vote between voter and target.
     *
     * @param voterDiscordId The voter's Discord ID
     * @param targetDiscordId The target's Discord ID
     * @return The vote if exists, empty otherwise
     */
    public Optional<ReputationVote> getVoteByVoterAndTarget(String voterDiscordId, String targetDiscordId) {
        String sql = """
            SELECT id, voter_discord_id, target_discord_id, vote_value, comment, comment_length, voted_at
            FROM reputation_votes
            WHERE voter_discord_id = ? AND target_discord_id = ?
            """;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterDiscordId);
            ps.setString(2, targetDiscordId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ReputationVote(
                        rs.getLong("id"),
                        rs.getString("voter_discord_id"),
                        rs.getString("target_discord_id"),
                        rs.getInt("vote_value"),
                        rs.getString("comment"),
                        rs.getInt("comment_length"),
                        rs.getTimestamp("voted_at").toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting vote from {} to {}", voterDiscordId, targetDiscordId, e);
        }

        return Optional.empty();
    }

    /**
     * Gets recent votes for a target (for display purposes).
     *
     * @param targetDiscordId The target's Discord ID
     * @param limit Maximum number of votes to return
     * @return List of recent votes
     */
    public List<ReputationVote> getRecentVotes(String targetDiscordId, int limit) {
        String sql = """
            SELECT id, voter_discord_id, target_discord_id, vote_value, comment, comment_length, voted_at
            FROM reputation_votes
            WHERE target_discord_id = ?
            ORDER BY voted_at DESC
            LIMIT ?
            """;

        List<ReputationVote> votes = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, targetDiscordId);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    votes.add(new ReputationVote(
                        rs.getLong("id"),
                        rs.getString("voter_discord_id"),
                        rs.getString("target_discord_id"),
                        rs.getInt("vote_value"),
                        rs.getString("comment"),
                        rs.getInt("comment_length"),
                        rs.getTimestamp("voted_at").toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting recent votes for {}", targetDiscordId, e);
        }

        return votes;
    }

    /**
     * Gets votes cast within a specific timeframe (for anti-abuse detection).
     *
     * @param voterDiscordId The voter's Discord ID
     * @param since Only get votes after this time
     * @return List of votes
     */
    public List<ReputationVote> getVotesInTimeframe(String voterDiscordId, Instant since) {
        String sql = """
            SELECT id, voter_discord_id, target_discord_id, vote_value, comment, comment_length, voted_at
            FROM reputation_votes
            WHERE voter_discord_id = ? AND voted_at >= ?
            ORDER BY voted_at DESC
            """;

        List<ReputationVote> votes = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterDiscordId);
            ps.setTimestamp(2, Timestamp.from(since));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    votes.add(new ReputationVote(
                        rs.getLong("id"),
                        rs.getString("voter_discord_id"),
                        rs.getString("target_discord_id"),
                        rs.getInt("vote_value"),
                        rs.getString("comment"),
                        rs.getInt("comment_length"),
                        rs.getTimestamp("voted_at").toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting votes in timeframe for {}", voterDiscordId, e);
        }

        return votes;
    }

    /**
     * Updates voter statistics.
     *
     * @param stats The voter statistics to update
     * @return true if successful
     */
    public boolean updateVoterStats(ReputationVoterStats stats) {
        String sql = """
            INSERT INTO reputation_voter_stats
            (voter_discord_id, account_created_at, total_votes_cast, positive_votes_cast, negative_votes_cast,
             votes_last_24h, last_vote_at, consensus_agreements, consensus_disagreements, credibility_score)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              total_votes_cast = VALUES(total_votes_cast),
              positive_votes_cast = VALUES(positive_votes_cast),
              negative_votes_cast = VALUES(negative_votes_cast),
              votes_last_24h = VALUES(votes_last_24h),
              last_vote_at = VALUES(last_vote_at),
              consensus_agreements = VALUES(consensus_agreements),
              consensus_disagreements = VALUES(consensus_disagreements),
              credibility_score = VALUES(credibility_score)
            """;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, stats.voterDiscordId());
            ps.setTimestamp(2, Timestamp.from(stats.accountCreatedAt()));
            ps.setInt(3, stats.totalVotesCast());
            ps.setInt(4, stats.positiveVotesCast());
            ps.setInt(5, stats.negativeVotesCast());
            ps.setInt(6, stats.votesLast24h());

            if (stats.lastVoteAt() != null) {
                ps.setTimestamp(7, Timestamp.from(stats.lastVoteAt()));
            } else {
                ps.setNull(7, Types.TIMESTAMP);
            }

            ps.setInt(8, stats.consensusAgreements());
            ps.setInt(9, stats.consensusDisagreements());
            ps.setDouble(10, stats.credibilityScore());

            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            logger.error("Error updating voter stats for {}", stats.voterDiscordId(), e);
            return false;
        }
    }

    /**
     * Gets voter statistics.
     *
     * @param voterDiscordId The voter's Discord ID
     * @return Voter statistics if exists, empty otherwise
     */
    public Optional<ReputationVoterStats> getVoterStats(String voterDiscordId) {
        String sql = """
            SELECT voter_discord_id, account_created_at, total_votes_cast, positive_votes_cast, negative_votes_cast,
                   votes_last_24h, last_vote_at, consensus_agreements, consensus_disagreements, credibility_score
            FROM reputation_voter_stats
            WHERE voter_discord_id = ?
            """;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, voterDiscordId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp lastVoteTs = rs.getTimestamp("last_vote_at");
                    return Optional.of(new ReputationVoterStats(
                        rs.getString("voter_discord_id"),
                        rs.getTimestamp("account_created_at").toInstant(),
                        rs.getInt("total_votes_cast"),
                        rs.getInt("positive_votes_cast"),
                        rs.getInt("negative_votes_cast"),
                        rs.getInt("votes_last_24h"),
                        lastVoteTs != null ? lastVoteTs.toInstant() : null,
                        rs.getInt("consensus_agreements"),
                        rs.getInt("consensus_disagreements"),
                        rs.getDouble("credibility_score")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting voter stats for {}", voterDiscordId, e);
        }

        return Optional.empty();
    }

    /**
     * Updates reputation cache for a user.
     *
     * @param cache The reputation cache to update
     * @return true if successful
     */
    public boolean updateReputationCache(ReputationCache cache) {
        String sql = """
            INSERT INTO reputation_cache
            (discord_id, total_score, display_score, last_calculated, total_votes_received, percentile_rank)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              total_score = VALUES(total_score),
              display_score = VALUES(display_score),
              last_calculated = VALUES(last_calculated),
              total_votes_received = VALUES(total_votes_received),
              percentile_rank = VALUES(percentile_rank)
            """;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, cache.discordId());
            ps.setDouble(2, cache.totalScore());
            ps.setInt(3, cache.displayScore());
            ps.setTimestamp(4, Timestamp.from(cache.lastCalculated()));
            ps.setInt(5, cache.totalVotesReceived());
            ps.setDouble(6, cache.percentileRank());

            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        } catch (SQLException e) {
            logger.error("Error updating reputation cache for {}", cache.discordId(), e);
            return false;
        }
    }

    /**
     * Gets reputation cache for a user.
     *
     * @param discordId The user's Discord ID
     * @return Reputation cache if exists, empty otherwise
     */
    public Optional<ReputationCache> getReputationCache(String discordId) {
        String sql = """
            SELECT discord_id, total_score, display_score, last_calculated, total_votes_received, percentile_rank
            FROM reputation_cache
            WHERE discord_id = ?
            """;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ReputationCache(
                        rs.getString("discord_id"),
                        rs.getDouble("total_score"),
                        rs.getInt("display_score"),
                        rs.getTimestamp("last_calculated").toInstant(),
                        rs.getInt("total_votes_received"),
                        rs.getDouble("percentile_rank")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting reputation cache for {}", discordId, e);
        }

        return Optional.empty();
    }

    /**
     * Gets all reputation scores for percentile calculation.
     *
     * @return List of all reputation scores
     */
    public List<ReputationCache> getAllReputationScores() {
        String sql = """
            SELECT discord_id, total_score, display_score, last_calculated, total_votes_received, percentile_rank
            FROM reputation_cache
            ORDER BY display_score DESC
            """;

        List<ReputationCache> scores = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                scores.add(new ReputationCache(
                    rs.getString("discord_id"),
                    rs.getDouble("total_score"),
                    rs.getInt("display_score"),
                    rs.getTimestamp("last_calculated").toInstant(),
                    rs.getInt("total_votes_received"),
                    rs.getDouble("percentile_rank")
                ));
            }
        } catch (SQLException e) {
            logger.error("Error getting all reputation scores", e);
        }

        return scores;
    }

    public void close() {
        dataSource.close();
    }

    /**
     * Adds a moderation action to the database.
     *
     * @param discordId The Discord ID of the user being moderated
     * @param minecraftUuid The Minecraft UUID if linked (nullable)
     * @param actionType The type of action (WARN or BAN)
     * @param reason The reason for the action
     * @param issuedBy The Discord ID of the staff member
     * @return The ID of the inserted record, or -1 if failed
     */
    public long addModerationAction(String discordId, String minecraftUuid, String actionType, String reason, String issuedBy) {
        return addModerationAction(discordId, minecraftUuid, actionType, reason, issuedBy, null);
    }

    /**
     * Adds a moderation action to the database with optional duration.
     *
     * @param discordId The Discord ID of the user being moderated
     * @param minecraftUuid The Minecraft UUID if linked (nullable)
     * @param actionType The type of action (WARN, BAN, or UNBAN)
     * @param reason The reason for the action
     * @param issuedBy The Discord ID of the staff member
     * @param duration The duration of the action (for bans, e.g. "30m", "2h", "Permanent") (nullable)
     * @return The ID of the inserted record, or -1 if failed
     */
    public long addModerationAction(String discordId, String minecraftUuid, String actionType, String reason, String issuedBy, String duration) {
        String sql = """
            INSERT INTO moderation_actions (discord_id, minecraft_uuid, action_type, reason, issued_by, issued_at, duration)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, discordId);
            if (minecraftUuid != null) {
                ps.setString(2, minecraftUuid);
            } else {
                ps.setNull(2, Types.VARCHAR);
            }
            ps.setString(3, actionType);
            ps.setString(4, reason);
            ps.setString(5, issuedBy);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            if (duration != null) {
                ps.setString(7, duration);
            } else {
                ps.setNull(7, Types.VARCHAR);
            }

            int rowsAffected = ps.executeUpdate();
            if (rowsAffected > 0) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error adding moderation action for Discord ID {}", discordId, e);
        }
        return -1;
    }

    /**
     * Gets moderation history for a user.
     *
     * @param discordId The Discord ID to look up
     * @return List of moderation actions
     */
    public List<ModerationAction> getModerationHistory(String discordId) {
        String sql = """
            SELECT id, discord_id, minecraft_uuid, action_type, reason, issued_by, issued_at, duration
            FROM moderation_actions
            WHERE discord_id = ?
            ORDER BY issued_at DESC
            """;

        List<ModerationAction> actions = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    actions.add(new ModerationAction(
                        rs.getLong("id"),
                        rs.getString("discord_id"),
                        rs.getString("minecraft_uuid"),
                        rs.getString("action_type"),
                        rs.getString("reason"),
                        rs.getString("issued_by"),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getString("duration")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting moderation history for Discord ID {}", discordId, e);
        }

        return actions;
    }

    /**
     * Gets recent moderation actions (for audit purposes).
     *
     * @param limit Maximum number of records to return
     * @return List of recent moderation actions
     */
    public List<ModerationAction> getRecentModerationActions(int limit) {
        String sql = """
            SELECT id, discord_id, minecraft_uuid, action_type, reason, issued_by, issued_at, duration
            FROM moderation_actions
            ORDER BY issued_at DESC
            LIMIT ?
            """;

        List<ModerationAction> actions = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    actions.add(new ModerationAction(
                        rs.getLong("id"),
                        rs.getString("discord_id"),
                        rs.getString("minecraft_uuid"),
                        rs.getString("action_type"),
                        rs.getString("reason"),
                        rs.getString("issued_by"),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getString("duration")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting recent moderation actions", e);
        }

        return actions;
    }

    /**
     * Record class for login IP information.
     */
    public record LoginIpInfo(String ipAddress, Instant loginTime, boolean allowed, String denyReason) {}

    /**
     * Record class for moderation actions.
     */
    public record ModerationAction(
        long id,
        String discordId,
        String minecraftUuid,
        String actionType,
        String reason,
        String issuedBy,
        Instant issuedAt,
        String duration
    ) {}

    /**
     * Record class for reputation votes.
     */
    public record ReputationVote(
        long id,
        String voterDiscordId,
        String targetDiscordId,
        int voteValue,
        String comment,
        int commentLength,
        Instant votedAt
    ) {}

    /**
     * Record class for reputation cache.
     */
    public record ReputationCache(
        String discordId,
        double totalScore,
        int displayScore,
        Instant lastCalculated,
        int totalVotesReceived,
        double percentileRank
    ) {}

    /**
     * Record class for reputation voter statistics.
     */
    public record ReputationVoterStats(
        String voterDiscordId,
        Instant accountCreatedAt,
        int totalVotesCast,
        int positiveVotesCast,
        int negativeVotesCast,
        int votesLast24h,
        Instant lastVoteAt,
        int consensusAgreements,
        int consensusDisagreements,
        double credibilityScore
    ) {}
}
