package com.confect1on.sentinel.db;

import com.confect1on.sentinel.config.SentinelConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import com.confect1on.sentinel.db.QuarantineInfo;
import java.sql.Types;

public class DatabaseManager {

    private final HikariDataSource dataSource;
    private final Logger logger;

    public DatabaseManager(SentinelConfig.MySQL config, Logger logger) {
        this.logger = logger;

        HikariConfig hikari = new HikariConfig();
        hikari.setDriverClassName("com.confect1on.sentinel.lib.mysql.jdbc.Driver");
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

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(createLinked);
            st.executeUpdate(createPending);
            st.executeUpdate(createQuarantines);
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
     */
    public boolean removeLinkByDiscordId(String discordId) {
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

    public void close() {
        dataSource.close();
    }
}
