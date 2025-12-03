package com.confect1on.sentinel.listener;

import com.confect1on.sentinel.db.DatabaseManager;
import com.confect1on.sentinel.db.QuarantineInfo;
import com.confect1on.sentinel.config.SentinelConfig;
import com.confect1on.sentinel.discord.DiscordManager;
import com.confect1on.sentinel.impersonation.ImpersonationManager;
import com.confect1on.sentinel.tos.TosManager;
import com.confect1on.sentinel.util.IpLogger;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.Optional;

public class LoginListener {

    private final DatabaseManager database;
    private final Logger logger;
    private final SentinelConfig config;
    private final DiscordManager discordManager;
    private final ImpersonationManager impersonationManager;
    private final TosManager tosManager;
    private final IpLogger ipLogger;

    public LoginListener(DatabaseManager database, SentinelConfig config, DiscordManager discordManager, ImpersonationManager impersonationManager, TosManager tosManager, IpLogger ipLogger, Logger logger) {
        this.database = database;
        this.config = config;
        this.discordManager = discordManager;
        this.impersonationManager = impersonationManager;
        this.tosManager = tosManager;
        this.ipLogger = ipLogger;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onLogin(LoginEvent event) {
        UUID uuid = event.getPlayer().getGameProfile().getId();
        String username = event.getPlayer().getUsername();
        
        // Get IP address for logging
        String ipAddress = event.getPlayer().getRemoteAddress().getAddress().getHostAddress();
        
        // Check if this is an impersonated UUID and get the original if so
        UUID originalUuid = uuid;
        boolean isImpersonating = false;
        if (impersonationManager != null && impersonationManager.isImpersonating(uuid)) {
            originalUuid = impersonationManager.getOriginalUuid(uuid);
            isImpersonating = true;
            logger.info("Player {} is impersonating, checking link status for original UUID {}", username, originalUuid);
        }
        
        // Get the virtual host they're connecting through
        String virtualHost = event.getPlayer().getVirtualHost()
            .map(host -> host.getHostString())
            .orElse("");

        // Check if this is a bypass server based on the virtual host
        if (virtualHost != null && !virtualHost.isEmpty()) {
            for (String server : config.bypassServers.servers) {
                if (virtualHost.toLowerCase().contains(server.toLowerCase())) {
                    logger.info("✅ {} ({}) connecting through bypass virtual host {}. Allowing login.", 
                        username, uuid, virtualHost);
                    
                    // Log successful bypass login
                    if (ipLogger != null) {
                        ipLogger.logLogin(originalUuid, null, ipAddress, true, null);
                    }
                    
                    event.setResult(ComponentResult.allowed());
                    return;
                }
            }
        }

        try {
            if (database.isLinked(originalUuid)) {
                String discordId = database.getDiscordId(originalUuid);
                
                // Check if Discord user is still in the server and handle cleanup
                if (discordManager != null && discordManager.getQuarantineChecker() != null) {
                    boolean isStillInDiscord = isDiscordUserStillInServer(discordId);
                    
                    if (!isStillInDiscord) {
                        // User was kicked/left Discord, they're no longer linked
                        logger.info("🔗 {} ({}) was linked but Discord user {} is no longer in server. Generating new link code.", 
                            username, originalUuid, discordId);
                        
                        // Generate a new link code
                        String code = generateCode();
                        database.savePendingCode(originalUuid, code);
                        
                        // Log denied login
                        if (ipLogger != null) {
                            ipLogger.logLogin(originalUuid, discordId, ipAddress, false, "Discord account no longer in server");
                        }
                        
                        event.setResult(ComponentResult.denied(
                            Component.text()
                                .append(Component.text("⚠  ACCOUNT NO LONGER LINKED\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.RED)
                                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .append(Component.text("Your Discord account is no longer linked.\n\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                                .append(Component.text("Your link code: ")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                                .append(Component.text(code)
                                    .color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                                .append(Component.text("\n\nTo re-link your account:\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GREEN))
                                .append(Component.text("   1. Join our Discord server\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .append(Component.text("   2. Run ")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .append(Component.text("/link " + code)
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                                .append(Component.text("\n\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .build()
                        ));
                        return;
                    }
                    
                    // Check and clean up any expired quarantine immediately on login
                    boolean cleanedUp = discordManager.getQuarantineChecker().checkAndCleanupExpiredQuarantine(discordId);
                    if (cleanedUp) {
                        logger.info("✅ {} ({}) had an expired quarantine that was cleaned up on login", username, uuid);
                    }
                    
                    // Check for active quarantine (after cleanup)
                    Optional<QuarantineInfo> quarantine = discordManager.getQuarantineChecker().getQuarantineInfo(discordId);
                    if (quarantine.isPresent()) {
                        logger.debug("🚫 {} ({}) is quarantined. Denying login.", username, uuid);
                        String quarantineMessage = discordManager.getQuarantineChecker().formatQuarantineMessage(quarantine.get());
                        
                        // Log denied login
                        if (ipLogger != null) {
                            ipLogger.logLogin(originalUuid, discordId, ipAddress, false, "Quarantined");
                        }
                        
                        event.setResult(ComponentResult.denied(Component.text(quarantineMessage)));
                        return;
                    }
                }
                
                // Check ToS acceptance
                if (tosManager != null && tosManager.isEnforced()) {
                    if (!tosManager.hasAgreedToCurrentVersion(discordId)) {
                        logger.info("❌ {} ({}) has not accepted ToS v{}. Denying login.", 
                            username, originalUuid, tosManager.getCurrentVersion());
                        
                        // Log denied login
                        if (ipLogger != null) {
                            ipLogger.logLogin(originalUuid, discordId, ipAddress, false, "ToS not accepted");
                        }
                        
                        event.setResult(ComponentResult.denied(
                            Component.text()
                                .append(Component.text("⚠  TERMS OF SERVICE UPDATE\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .append(Component.text("We have updated our Terms of Service.\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                                .append(Component.text("You must accept the new terms to continue playing.\n\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                                .append(Component.text("To accept the terms:\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.AQUA))
                                .append(Component.text("   1. Go to our Discord server\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .append(Component.text("   2. Run ")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .append(Component.text("/tos")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                                .append(Component.text(" in any channel\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .append(Component.text("   3. Click the 'I Agree' button\n\n")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                                .build()
                        ));
                        return;
                    }
                }
                
                // save the current username each login for quick lookup
                // For impersonations, we still update with the original UUID but can use the impersonated username
                if (!isImpersonating) {
                    database.updateUsername(originalUuid, username);
                }

                logger.debug("✅ {} ({}) is linked. Allowing login.", username, originalUuid);
                
                // Log successful login
                if (ipLogger != null) {
                    ipLogger.logLogin(originalUuid, discordId, ipAddress, true, null);
                }
                
                event.setResult(ComponentResult.allowed());
            } else {
                // Check if they were linked but removed due to leaving Discord
                String discordId = database.getDiscordId(originalUuid);
                if (discordId != null) {
                    // THIS SHOULD NEVER HAPPEN!
                    logger.warn("🔗 {} ({}) was linked but needs to relink.", username, originalUuid);
                    
                    // Log denied login
                    if (ipLogger != null) {
                        ipLogger.logLogin(originalUuid, discordId, ipAddress, false, "Account needs relinking");
                    }
                    
                    event.setResult(ComponentResult.denied(
                            Component.text("Your Discord account is no longer linked.\n" +
                                    "Please contact an administrator to relink your account.")
                    ));
                    return;
                }
                
                // generate & rotate the code
                String code = generateCode();
                database.savePendingCode(originalUuid, code);

                logger.info("❌ {} ({}) is not linked. Generated code: {}", username, originalUuid, code);
                
                // Log denied login
                if (ipLogger != null) {
                    ipLogger.logLogin(originalUuid, null, ipAddress, false, "Not linked");
                }
                
                event.setResult(ComponentResult.denied(
                    Component.text()
                        .append(Component.text("⚠  ACCOUNT NOT LINKED\n")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                        .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(Component.text("This Minecraft account must be linked to Discord.\n\n")
                            .color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                        .append(Component.text("Your link code: ")
                            .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                        .append(Component.text(code)
                            .color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                        .append(Component.text("\n\n🔗 How to link:\n")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GREEN))
                        .append(Component.text("   1. Join our Discord server\n")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(Component.text("   2. Run ")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(Component.text("/link " + code)
                            .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                        .append(Component.text(" in any channel\n")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(Component.text("   3. You'll be able to join immediately!\n\n")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .build()
                ));
            }
        } catch (Exception e) {
            logger.error("⚠️ Error during login check for {} ({})", username, originalUuid, e);
            
            // Log error
            if (ipLogger != null) {
                ipLogger.logLogin(originalUuid, null, ipAddress, false, "Server error");
            }
            
            event.setResult(ComponentResult.denied(
                Component.text()
                    .append(Component.text("❌  SERVER ERROR\n")
                        .color(net.kyori.adventure.text.format.NamedTextColor.DARK_RED)
                        .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                    .append(Component.text("A server error occurred.\n")
                        .color(net.kyori.adventure.text.format.NamedTextColor.RED))
                    .append(Component.text("Please try again in a few moments.\n\n")
                        .color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                    .append(Component.text("If this issue persists, please contact an administrator.\n\n")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                    .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                    .build()
            ));
        }
    }

    /**
     * Checks if a Discord user is still in the server.
     * If they're not, removes them from the database.
     */
    private boolean isDiscordUserStillInServer(String discordId) {
        if (discordId == null) return false;
        
        try {
            // Use the QuarantineChecker's method to check if user is still in Discord
            return discordManager.getQuarantineChecker().isUserStillInDiscord(discordId);
        } catch (Exception e) {
            logger.error("🔗 Error checking if Discord user {} is still in server", discordId, e);
            return true; // Default to assuming they're still there on error
        }
    }

    private String generateCode() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
