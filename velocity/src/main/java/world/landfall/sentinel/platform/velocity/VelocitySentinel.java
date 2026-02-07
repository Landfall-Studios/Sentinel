package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.BuildConstants;
import world.landfall.sentinel.LoginHandler;
import world.landfall.sentinel.SentinelCore;
import world.landfall.sentinel.config.ConfigLoader;
import world.landfall.sentinel.config.SentinelConfig;
import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.discord.DiscordManager;
import world.landfall.sentinel.impersonation.ImpersonationManager;
import world.landfall.sentinel.tos.TosManager;
import world.landfall.sentinel.util.IpLogger;
import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import javax.security.auth.login.LoginException;
import java.nio.file.Path;

@Plugin(id = "sentinel", name = "Sentinel", version = BuildConstants.VERSION)
public class VelocitySentinel {
    @Inject private Logger logger;
    @Inject private ProxyServer server;
    @Inject @DataDirectory private Path dataDirectory;

    private DatabaseManager database;
    private DiscordManager discord;
    private ImpersonationManager impersonationManager;
    private VelocityPlatformAdapter platformAdapter;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Starting Sentinel...");

        // Initialize platform adapter
        platformAdapter = new VelocityPlatformAdapter(server, dataDirectory);
        SentinelCore.init(platformAdapter, logger);

        var config = ConfigLoader.loadConfig(dataDirectory, logger);

        try {
            database = new DatabaseManager(config.mysql, logger);
        } catch (RuntimeException e) {
            logger.error("Disabled: DB connection failed.");
            return;
        }

        // Start Discord if we have a token
        if (config.discord.token != null && !config.discord.token.isBlank()) {
            try {
                discord = new DiscordManager(database, config.discord.token, config.discord.linkedRole,
                        config.discord.quarantineRole, config.discord.staffRoles, platformAdapter, config, logger);
                discord.start();
            } catch (LoginException e) {
                logger.error("Failed to start Discord bot", e);
            }
        } else {
            logger.warn("Failed to start Discord bot, no token provided!");
        }

        // Initialize impersonation manager if enabled
        if (config.impersonation.enabled) {
            impersonationManager = new ImpersonationManager(dataDirectory, logger);
            impersonationManager.setAllowedUsers(config.impersonation.allowedUsers);

            // Register the impersonation command
            VelocityImpersonateCommand impersonateCommand = new VelocityImpersonateCommand(impersonationManager, logger);
            server.getCommandManager().register("simulacra", impersonateCommand, "sim");

            // Register the game profile listener (runs EARLY to modify profile before login check)
            server.getEventManager().register(this, new VelocityGameProfileListener(impersonationManager, logger));

            // Register disconnect listener to clean up active impersonations
            server.getEventManager().register(this, new VelocityDisconnectListener(impersonationManager, logger));

            logger.info("Impersonation feature enabled with {} allowed users", config.impersonation.allowedUsers.length);
        }

        // Register reputation command if Discord is enabled
        if (discord != null && discord.getReputationManager() != null) {
            VelocityReputationCommand repCommand = new VelocityReputationCommand(database, discord.getReputationManager(), logger);
            server.getCommandManager().register("rep", repCommand, "reputation");
            logger.info("Reputation command registered");
        }

        // Initialize ToS manager and IP logger
        TosManager tosManager = null;
        IpLogger ipLogger = null;

        if (discord != null && discord.getTosManager() != null) {
            tosManager = discord.getTosManager();
        }

        if (config.tos.ipLogging) {
            ipLogger = new IpLogger(database, config.tos, logger);
        }

        // Create the platform-independent login handler
        LoginHandler loginHandler = new LoginHandler(database, config, discord, impersonationManager, tosManager, ipLogger, logger);

        // Register the Velocity login listener (after Discord, ToS, and impersonation are initialized)
        server.getEventManager().register(this, new VelocityLoginListener(loginHandler));

        logger.info("Sentinel up and running.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (discord != null) discord.shutdown();
        if (database != null) {
            database.close();
            logger.info("Database pool closed.");
        }
    }
}
