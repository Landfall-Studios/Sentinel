package world.landfall.sentinel;

import world.landfall.sentinel.config.SentinelConfig;
import world.landfall.sentinel.context.GamePlatform;
import world.landfall.sentinel.context.LoginContext;
import world.landfall.sentinel.context.LoginGatekeeper;
import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.db.QuarantineInfo;
import world.landfall.sentinel.discord.DiscordManager;
import world.landfall.sentinel.impersonation.ImpersonationManager;
import world.landfall.sentinel.tos.TosManager;
import world.landfall.sentinel.util.IpLogger;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Platform-independent login business logic.
 * Extracted from the old Velocity LoginListener.
 */
public class LoginHandler {

    private final DatabaseManager database;
    private final Logger logger;
    private final SentinelConfig config;
    private final DiscordManager discordManager;
    private final ImpersonationManager impersonationManager;
    private final TosManager tosManager;
    private final IpLogger ipLogger;

    public LoginHandler(DatabaseManager database, SentinelConfig config, DiscordManager discordManager,
                        ImpersonationManager impersonationManager, TosManager tosManager,
                        IpLogger ipLogger, Logger logger) {
        this.database = database;
        this.config = config;
        this.discordManager = discordManager;
        this.impersonationManager = impersonationManager;
        this.tosManager = tosManager;
        this.ipLogger = ipLogger;
        this.logger = logger;
    }

    /**
     * Core login handling logic. Determines whether to allow or deny the login
     * and delegates the actual allow/deny action to the platform-specific gatekeeper.
     */
    public void handleLogin(LoginContext ctx, LoginGatekeeper gatekeeper) {
        UUID uuid = ctx.getPlayerUuid();
        String username = ctx.getPlayerUsername();
        String ipAddress = ctx.getIpAddress();
        GamePlatform platform = ctx.getPlatform();

        // Check if this is an impersonated UUID and get the original if so
        UUID originalUuid = uuid;
        boolean isImpersonating = false;
        if (impersonationManager != null && impersonationManager.isImpersonating(uuid)) {
            originalUuid = impersonationManager.getOriginalUuid(uuid);
            isImpersonating = true;
            logger.info("Player {} is impersonating, checking link status for original UUID {}", username, originalUuid);
        }

        // Get the virtual host they're connecting through
        String virtualHost = ctx.getVirtualHost().orElse("");

        // Check if this is a bypass server based on the virtual host
        if (gatekeeper.supportsBypassRouting() && virtualHost != null && !virtualHost.isEmpty()) {
            for (String server : config.bypassServers.servers) {
                if (virtualHost.toLowerCase().contains(server.toLowerCase())) {
                    logger.info("Player {} ({}) connecting through bypass virtual host {}. Allowing login.",
                            username, uuid, virtualHost);

                    if (ipLogger != null) {
                        ipLogger.logLogin(originalUuid, null, ipAddress, true, null);
                    }

                    gatekeeper.allowLogin(ctx);
                    return;
                }
            }
        }

        try {
            if (database.isLinked(originalUuid, platform)) {
                String discordId = database.getDiscordId(originalUuid, platform);

                // Check if Discord user is still in the server
                if (discordManager != null && discordManager.getQuarantineChecker() != null) {
                    boolean isStillInDiscord = isDiscordUserStillInServer(discordId);

                    if (!isStillInDiscord) {
                        logger.info("Player {} ({}) was linked but Discord user {} is no longer in server. Generating new link code.",
                                username, originalUuid, discordId);

                        String code = generateCode();
                        database.savePendingCode(originalUuid, code, platform);

                        if (ipLogger != null) {
                            ipLogger.logLogin(originalUuid, discordId, ipAddress, false, "Discord account no longer in server");
                        }

                        gatekeeper.denyLogin(ctx, new DenialReason.DiscordLeft(code));
                        return;
                    }

                    // Check and clean up any expired quarantine immediately on login
                    boolean cleanedUp = discordManager.getQuarantineChecker().checkAndCleanupExpiredQuarantine(discordId);
                    if (cleanedUp) {
                        logger.info("Player {} ({}) had an expired quarantine that was cleaned up on login", username, uuid);
                    }

                    // Check for active quarantine (after cleanup)
                    Optional<QuarantineInfo> quarantine = discordManager.getQuarantineChecker().getQuarantineInfo(discordId);
                    if (quarantine.isPresent()) {
                        logger.debug("Player {} ({}) is quarantined. Denying login.", username, uuid);
                        QuarantineInfo q = quarantine.get();

                        if (ipLogger != null) {
                            ipLogger.logLogin(originalUuid, discordId, ipAddress, false, "Quarantined");
                        }

                        gatekeeper.denyLogin(ctx, new DenialReason.Quarantined(
                                q.reason(), q.getFormattedTimeRemaining(), q.isPermanent()));
                        return;
                    }
                }

                // Check ToS acceptance
                if (tosManager != null && tosManager.isEnforced()) {
                    if (!tosManager.hasAgreedToCurrentVersion(discordId)) {
                        logger.info("Player {} ({}) has not accepted ToS v{}. Denying login.",
                                username, originalUuid, tosManager.getCurrentVersion());

                        if (ipLogger != null) {
                            ipLogger.logLogin(originalUuid, discordId, ipAddress, false, "ToS not accepted");
                        }

                        gatekeeper.denyLogin(ctx, new DenialReason.TosNotAccepted(tosManager.getCurrentVersion()));
                        return;
                    }
                }

                // Save the current username each login for quick lookup
                if (!isImpersonating) {
                    database.updateUsername(originalUuid, username, platform);
                }

                logger.debug("Player {} ({}) is linked. Allowing login.", username, originalUuid);

                if (ipLogger != null) {
                    ipLogger.logLogin(originalUuid, discordId, ipAddress, true, null);
                }

                gatekeeper.allowLogin(ctx);
            } else {
                // Check if they were linked but removed due to leaving Discord
                String discordId = database.getDiscordId(originalUuid, platform);
                if (discordId != null) {
                    logger.warn("Player {} ({}) was linked but needs to relink.", username, originalUuid);

                    if (ipLogger != null) {
                        ipLogger.logLogin(originalUuid, discordId, ipAddress, false, "Account needs relinking");
                    }

                    gatekeeper.denyLogin(ctx, new DenialReason.NeedsRelink());
                    return;
                }

                // Generate & rotate the code
                String code = generateCode();
                database.savePendingCode(originalUuid, code, platform);

                logger.info("Player {} ({}) is not linked. Generated code: {}", username, originalUuid, code);

                if (ipLogger != null) {
                    ipLogger.logLogin(originalUuid, null, ipAddress, false, "Not linked");
                }

                gatekeeper.denyLogin(ctx, new DenialReason.NotLinked(code));
            }
        } catch (Exception e) {
            logger.error("Error during login check for {} ({})", username, originalUuid, e);

            if (ipLogger != null) {
                ipLogger.logLogin(originalUuid, null, ipAddress, false, "Server error");
            }

            gatekeeper.denyLogin(ctx, new DenialReason.ServerError());
        }
    }

    private boolean isDiscordUserStillInServer(String discordId) {
        if (discordId == null) return false;
        try {
            return discordManager.getQuarantineChecker().isUserStillInDiscord(discordId);
        } catch (Exception e) {
            logger.error("Error checking if Discord user {} is still in server", discordId, e);
            return true; // Default to assuming they're still there on error
        }
    }

    private String generateCode() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
