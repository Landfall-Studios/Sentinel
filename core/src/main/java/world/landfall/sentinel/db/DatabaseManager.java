package world.landfall.sentinel.db;

import world.landfall.sentinel.config.SentinelConfig;
import world.landfall.sentinel.context.GamePlatform;
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

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(createLinked);
            st.executeUpdate(createPending);
            st.executeUpdate(createQuarantines);
            st.executeUpdate(createTosAttestations);
            st.executeUpdate(createTosVersions);
            st.executeUpdate(createLoginIps);
            st.executeUpdate(createModerationActions);

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

            // Multi-platform linking migration: add platform column to linked_accounts
            try {
                st.executeUpdate("ALTER TABLE linked_accounts ADD COLUMN IF NOT EXISTS platform VARCHAR(16) NOT NULL DEFAULT 'MINECRAFT'");
                logger.info("Added platform column to linked_accounts table.");
            } catch (SQLException e) {
                logger.debug("Could not add platform column to linked_accounts (may already exist): {}", e.getMessage());
            }

            // Migrate linked_accounts PK to composite (uuid, platform)
            try {
                st.executeUpdate("ALTER TABLE linked_accounts DROP PRIMARY KEY, ADD PRIMARY KEY (uuid, platform)");
                logger.info("Migrated linked_accounts primary key to composite (uuid, platform).");
            } catch (SQLException e) {
                logger.debug("Could not migrate linked_accounts PK (may already be composite): {}", e.getMessage());
            }

            // Migrate linked_accounts UNIQUE on discord_id to composite (discord_id, platform)
            try {
                st.executeUpdate("ALTER TABLE linked_accounts DROP INDEX discord_id, ADD UNIQUE INDEX uq_discord_platform (discord_id, platform)");
                logger.info("Migrated linked_accounts discord_id unique to composite (discord_id, platform).");
            } catch (SQLException e) {
                logger.debug("Could not migrate linked_accounts discord_id unique (may already be composite): {}", e.getMessage());
            }

            // Multi-platform linking migration: add platform column to pending_links
            try {
                st.executeUpdate("ALTER TABLE pending_links ADD COLUMN IF NOT EXISTS platform VARCHAR(16) NOT NULL DEFAULT 'MINECRAFT'");
                logger.info("Added platform column to pending_links table.");
            } catch (SQLException e) {
                logger.debug("Could not add platform column to pending_links (may already exist): {}", e.getMessage());
            }

            // Migrate pending_links PK to composite (uuid, platform)
            try {
                st.executeUpdate("ALTER TABLE pending_links DROP PRIMARY KEY, ADD PRIMARY KEY (uuid, platform)");
                logger.info("Migrated pending_links primary key to composite (uuid, platform).");
            } catch (SQLException e) {
                logger.debug("Could not migrate pending_links PK (may already be composite): {}", e.getMessage());
            }
        } catch (SQLException e) {
            logger.error("Failed to init DB tables", e);
        }
    }

    public boolean isLinked(UUID uuid, GamePlatform platform) {
        String query = "SELECT 1 FROM linked_accounts WHERE uuid = ? AND platform = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, platform.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Failed to check linked status for UUID: {} platform: {}", uuid, platform, e);
            return false;
        }
    }

    /**
     * Cache or update the player's last-seen username.
     */
    public void updateUsername(UUID uuid, String username, GamePlatform platform) {
        String sql = "UPDATE linked_accounts SET username = ? WHERE uuid = ? AND platform = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, uuid.toString());
            ps.setString(3, platform.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Could not update username for {} on {}", uuid, platform, e);
        }
    }

    /**
     * Inserts or rotates the pending link code for this UUID and platform.
     */
    public void savePendingCode(UUID uuid, String code, GamePlatform platform) {
        String sql = """
            INSERT INTO pending_links (uuid, code, created_at, platform)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              code = VALUES(code),
              created_at = VALUES(created_at)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, code);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setString(4, platform.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to store pending link for {} on {}", uuid, platform, e);
        }
    }

    /**
     * Atomically claims a pending link code:
     *  - looks up the UUID and platform by code (with row lock)
     *  - deletes that pending row
     *  - returns the claimed PendingClaim (or null if none)
     *
     * Uses a transaction with SELECT ... FOR UPDATE to prevent two concurrent
     * /link calls from both claiming the same code.
     */
    public PendingClaim claimPending(String code) {
        String select = "SELECT uuid, platform FROM pending_links WHERE code = ? FOR UPDATE";
        String delete = "DELETE FROM pending_links WHERE code = ?";
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                UUID uuid;
                GamePlatform platform;
                try (PreparedStatement ps = conn.prepareStatement(select)) {
                    ps.setString(1, code);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return null;
                        }
                        uuid = UUID.fromString(rs.getString("uuid"));
                        platform = GamePlatform.valueOf(rs.getString("platform"));
                    }
                }
                try (PreparedStatement del = conn.prepareStatement(delete)) {
                    del.setString(1, code);
                    del.executeUpdate();
                }
                conn.commit();
                return new PendingClaim(uuid, platform);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to claim pending code {}", code, e);
            return null;
        }
    }

    /**
     * Attempts to insert into linked_accounts for the given platform.
     * Returns false if the Discord ID is already linked on this platform, true otherwise.
     *
     * Uses a transaction to prevent two concurrent calls from both passing
     * the duplicate check and inserting.
     */
    public boolean addLink(UUID uuid, String discordId, GamePlatform platform) {
        String check  = "SELECT 1 FROM linked_accounts WHERE discord_id = ? AND platform = ? FOR UPDATE";
        String insert = "INSERT INTO linked_accounts (uuid, discord_id, platform) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // ensure this Discord ID isn't already linked on this platform
                try (PreparedStatement ps = conn.prepareStatement(check)) {
                    ps.setString(1, discordId);
                    ps.setString(2, platform.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            conn.rollback();
                            return false;
                        }
                    }
                }
                // insert new link
                try (PreparedStatement ps2 = conn.prepareStatement(insert)) {
                    ps2.setString(1, uuid.toString());
                    ps2.setString(2, discordId);
                    ps2.setString(3, platform.name());
                    ps2.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to add link {} ↔ {} on {}", uuid, discordId, platform, e);
            return false;
        }
    }

    public List<LinkInfo> findByDiscordId(String discordId) {
        String sql = "SELECT uuid, discord_id, username, platform FROM linked_accounts WHERE discord_id = ?";
        List<LinkInfo> links = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    links.add(new LinkInfo(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("discord_id"),
                            rs.getString("username"),
                            GamePlatform.valueOf(rs.getString("platform"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Error looking up by Discord ID {}", discordId, e);
        }
        return links;
    }

    public Optional<LinkInfo> findByUsername(String username) {
        String sql = "SELECT uuid, discord_id, username, platform FROM linked_accounts WHERE username = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new LinkInfo(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("discord_id"),
                        rs.getString("username"),
                        GamePlatform.valueOf(rs.getString("platform"))
                ));
            }
        } catch (SQLException e) {
            logger.error("Error looking up by username {}", username, e);
            return Optional.empty();
        }
    }

    public Optional<LinkInfo> findByUsername(String username, GamePlatform platform) {
        String sql = "SELECT uuid, discord_id, username, platform FROM linked_accounts WHERE username = ? AND platform = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, platform.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new LinkInfo(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("discord_id"),
                        rs.getString("username"),
                        GamePlatform.valueOf(rs.getString("platform"))
                ));
            }
        } catch (SQLException e) {
            logger.error("Error looking up by username {} on {}", username, platform, e);
            return Optional.empty();
        }
    }

    /**
     * Gets all linked accounts for role synchronization.
     * Returns a list of all Discord IDs that should have the linked role.
     */
    public List<String> getAllLinkedDiscordIds() {
        String sql = "SELECT DISTINCT discord_id FROM linked_accounts";
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
     * Gets the Discord ID for a linked UUID on a specific platform.
     * Returns null if the UUID is not linked on that platform.
     */
    public String getDiscordId(UUID uuid, GamePlatform platform) {
        String sql = "SELECT discord_id FROM linked_accounts WHERE uuid = ? AND platform = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, platform.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("discord_id");
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting Discord ID for UUID {} on {}", uuid, platform, e);
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
     * Returns empty if no quarantine exists or if it has expired.
     *
     * @param discordId The Discord ID to check
     * @return QuarantineInfo if quarantined and active, empty otherwise
     */
    public Optional<QuarantineInfo> getActiveQuarantine(String discordId) {
        return getRawQuarantine(discordId).filter(QuarantineInfo::isActive);
    }

    /**
     * Gets the quarantine record for a Discord ID regardless of expiry status.
     * Used by cleanup routines that need to find and remove expired quarantines.
     *
     * @param discordId The Discord ID to check
     * @return QuarantineInfo if any quarantine row exists, empty otherwise
     */
    public Optional<QuarantineInfo> getRawQuarantine(String discordId) {
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

                return Optional.of(new QuarantineInfo(
                    rs.getString("discord_id"),
                    rs.getString("reason"),
                    expiresAt,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("created_by")
                ));
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
     * Record for a claimed pending link, including the platform it was created from.
     */
    public record PendingClaim(UUID uuid, GamePlatform platform) {}

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

}
