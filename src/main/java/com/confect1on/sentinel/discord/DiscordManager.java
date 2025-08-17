package com.confect1on.sentinel.discord;

import com.confect1on.sentinel.db.DatabaseManager;
import com.confect1on.sentinel.config.SentinelConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;

public class DiscordManager extends ListenerAdapter {
    private final DatabaseManager db;
    private final Logger logger;
    private final String token;
    private final String linkedRoleId;
    private final String quarantineRoleId;

    private final LinkCommandListener linkListener;
    private final WhoIsCommandListener whoisListener;
    private final QuarantineCommandListener quarantineListener;
    private final UnquarantineCommandListener unquarantineCommandListener;
    private RoleManager roleManager;
    private QuarantineChecker quarantineChecker;

    private JDA jda;

    public DiscordManager(DatabaseManager db, String token, String linkedRoleId, String quarantineRoleId, String[] staffRoles, ProxyServer proxyServer, SentinelConfig config, Logger logger) {
        this.db = db;
        this.token = token;
        this.linkedRoleId = linkedRoleId;
        this.quarantineRoleId = quarantineRoleId;
        this.logger = logger;

        this.linkListener = new LinkCommandListener(db, logger);
        this.whoisListener = new WhoIsCommandListener(db, logger);
        this.quarantineListener = new QuarantineCommandListener(db, quarantineRoleId, staffRoles, proxyServer, config, logger);
        this.unquarantineCommandListener = new UnquarantineCommandListener(db, staffRoles, quarantineRoleId, logger);
    }

    public void start() throws LoginException {
        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(linkListener, whoisListener, quarantineListener, unquarantineCommandListener, this)
                .build();

        // Register slash commands
        jda.updateCommands().addCommands(
            linkListener.getCommandData(),
            quarantineListener.getCommandData(),
            unquarantineCommandListener.getCommandData(),
            whoisListener.getCommandData()
        ).queue();

        logger.info("[Sentinel] Discord bot started with /link, /whois, /quarantine, and /unquarantine.");
    }

    @Override
    public void onReady(@Nonnull ReadyEvent event) {
        // Initialize role manager once JDA is ready
        if (linkedRoleId != null && !linkedRoleId.isBlank()) {
            roleManager = new RoleManager(db, jda, linkedRoleId, logger);
            roleManager.startRoleSynchronization();
            
            // Set the role manager in the link listener so it can assign roles to new links
            linkListener.setRoleManager(roleManager);
        }
        
        // Initialize quarantine checker
        quarantineChecker = new QuarantineChecker(db, jda, quarantineRoleId, logger);
    }

    /**
     * Gets the quarantine checker for login validation.
     */
    public QuarantineChecker getQuarantineChecker() {
        return quarantineChecker;
    }

    public void shutdown() {
        if (roleManager != null) {
            roleManager.shutdown();
        }
        if (quarantineChecker != null) {
            quarantineChecker.shutdown();
        }
        if (jda != null) {
            jda.shutdown();
            logger.info("[Sentinel] Discord bot shut down.");
        }
    }
}
