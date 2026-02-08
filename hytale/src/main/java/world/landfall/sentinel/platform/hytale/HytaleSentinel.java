package world.landfall.sentinel.platform.hytale;

import world.landfall.sentinel.LoginHandler;
import world.landfall.sentinel.SentinelCore;
import world.landfall.sentinel.config.ConfigLoader;
import world.landfall.sentinel.config.SentinelConfig;
import world.landfall.sentinel.db.DatabaseManager;
import world.landfall.sentinel.discord.DiscordManager;
import world.landfall.sentinel.tos.TosManager;
import world.landfall.sentinel.util.IpLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import java.nio.file.Path;

/**
 * Hytale plugin entry point for Sentinel.
 */
public class HytaleSentinel extends JavaPlugin {

    private Logger logger;
    private DatabaseManager database;
    private DiscordManager discord;
    private HytalePlatformAdapter platformAdapter;

    public HytaleSentinel(@Nonnull JavaPluginInit init) {
        super(init);
        this.logger = new HytaleLoggerAdapter(getLogger());
    }

    @Override
    protected void setup() {
        super.setup();
    }

    @Override
    protected void start() {
        super.start();
        logger.info("Starting Sentinel...");

        Path dataDirectory = getDataDirectory();

        // Initialize platform adapter
        platformAdapter = new HytalePlatformAdapter(dataDirectory);
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

        // No impersonation on Hytale (no profile swapping)

        // Initialize ToS manager and IP logger
        TosManager tosManager = null;
        IpLogger ipLogger = null;

        if (discord != null && discord.getTosManager() != null) {
            tosManager = discord.getTosManager();
        }

        if (config.tos.ipLogging) {
            ipLogger = new IpLogger(database, config.tos, logger);
        }

        // Create the platform-independent login handler (null ImpersonationManager)
        LoginHandler loginHandler = new LoginHandler(database, config, discord, null, tosManager, ipLogger, logger);

        // Register event listeners
        HytaleLoginListener loginListener = new HytaleLoginListener(loginHandler);
        this.getEventRegistry().registerGlobal(PlayerConnectEvent.class, loginListener::onPlayerConnect);

        HytaleDisconnectListener disconnectListener = new HytaleDisconnectListener(logger);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, disconnectListener::onPlayerDisconnect);

        logger.info("Sentinel up and running.");
    }

    @Override
    protected void shutdown() {
        logger.info("Shutting down Sentinel...");

        if (platformAdapter != null) {
            platformAdapter.getScheduler().shutdown();
        }
        if (discord != null) {
            discord.shutdown();
        }
        if (database != null) {
            database.close();
            logger.info("Database pool closed.");
        }

        super.shutdown();
    }
}
