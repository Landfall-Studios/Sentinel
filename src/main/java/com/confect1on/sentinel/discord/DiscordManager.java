package com.confect1on.sentinel.discord;

import com.confect1on.sentinel.db.DatabaseManager;
import com.confect1on.sentinel.config.SentinelConfig;
import com.confect1on.sentinel.tos.TosManager;
import com.confect1on.sentinel.moderation.ModerationManager;
import com.confect1on.sentinel.reputation.ReputationManager;
import com.confect1on.sentinel.reputation.ReputationScheduler;
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
    private final SentinelConfig config;

    private final LinkCommandListener linkListener;
    private final WhoIsCommandListener whoisListener;
    private TosCommandListener tosListener;
    private NoteCommandListener noteListener;
    private WarnCommandListener warnListener;
    private BanCommandListener banListener;
    private UnbanCommandListener unbanListener;
    private HistoryCommandListener historyListener;
    private ReputationCommandListener reputationListener;
    private RoleManager roleManager;
    private QuarantineChecker quarantineChecker;
    private TosManager tosManager;
    private ModerationManager moderationManager;
    private ReputationManager reputationManager;
    private ReputationScheduler reputationScheduler;

    private JDA jda;

    public DiscordManager(DatabaseManager db, String token, String linkedRoleId, String quarantineRoleId, String[] staffRoles, ProxyServer proxyServer, SentinelConfig config, Logger logger) {
        this.db = db;
        this.token = token;
        this.linkedRoleId = linkedRoleId;
        this.quarantineRoleId = quarantineRoleId;
        this.config = config;
        this.logger = logger;

        this.linkListener = new LinkCommandListener(db, logger);
        this.whoisListener = new WhoIsCommandListener(db, logger);
        
        // Initialize ToS components if configured
        if (config.tos.enforcement) {
            this.tosManager = new TosManager(db, config.tos, logger);
            this.tosListener = new TosCommandListener(db, tosManager, config.discord.tosAuditChannel, logger);
            this.linkListener.setTosManager(tosManager);
            this.linkListener.setTosCommandListener(tosListener);
        }
        
        // Initialize moderation components
        this.moderationManager = new ModerationManager(db, config.discord.moderationAuditChannel, logger);
        this.noteListener = new NoteCommandListener(db, moderationManager, staffRoles, logger);
        this.warnListener = new WarnCommandListener(db, moderationManager, staffRoles, logger);
        this.banListener = new BanCommandListener(db, moderationManager, staffRoles, quarantineRoleId, proxyServer, config, logger);
        this.unbanListener = new UnbanCommandListener(db, moderationManager, staffRoles, quarantineRoleId, logger);
        this.historyListener = new HistoryCommandListener(db, moderationManager, staffRoles, logger);

        // Initialize reputation system
        this.reputationManager = new ReputationManager(db, config.reputation, logger);
        this.reputationListener = new ReputationCommandListener(db, reputationManager, logger);
    }

    public void start() throws LoginException {
        // Build event listeners list
        var listeners = new java.util.ArrayList<>();
        listeners.add(linkListener);
        listeners.add(whoisListener);
        if (tosListener != null) {
            listeners.add(tosListener);
        }
        listeners.add(noteListener);
        listeners.add(warnListener);
        listeners.add(banListener);
        listeners.add(unbanListener);
        listeners.add(historyListener);
        listeners.add(reputationListener);
        listeners.add(this);
        
        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(listeners.toArray())
                .build();

        // Build commands list
        var commands = new java.util.ArrayList<net.dv8tion.jda.api.interactions.commands.build.SlashCommandData>();
        commands.add(linkListener.getCommandData());
        commands.add(whoisListener.getCommandData());
        if (tosListener != null) {
            commands.add(tosListener.getCommandData());
        }
        commands.add(noteListener.getCommandData());
        commands.add(warnListener.getCommandData());
        commands.add(banListener.getCommandData());
        commands.add(unbanListener.getCommandData());
        commands.add(historyListener.getCommandData());
        commands.add(reputationListener.getCommandData());

        // Register slash commands
        jda.updateCommands().addCommands(commands).queue();

        String commandList = tosListener != null ?
            "/link, /whois, /tos, /note, /warn, /ban, /unban, /history, and /rep" :
            "/link, /whois, /note, /warn, /ban, /unban, /history, and /rep";
        logger.info("[Sentinel] Discord bot started with {}.", commandList);
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

        // Set JDA for moderation manager
        if (moderationManager != null) {
            moderationManager.setJDA(jda);
        }

        // Initialize reputation scheduler
        if (reputationManager != null) {
            reputationScheduler = new ReputationScheduler(db, reputationManager,
                config.reputation, logger);
        }
    }

    /**
     * Gets the quarantine checker for login validation.
     */
    public QuarantineChecker getQuarantineChecker() {
        return quarantineChecker;
    }
    
    /**
     * Gets the ToS manager for login validation.
     */
    public TosManager getTosManager() {
        return tosManager;
    }

    public void shutdown() {
        if (roleManager != null) {
            roleManager.shutdown();
        }
        if (quarantineChecker != null) {
            quarantineChecker.shutdown();
        }
        if (reputationScheduler != null) {
            reputationScheduler.shutdown();
        }
        if (jda != null) {
            jda.shutdown();
            logger.info("[Sentinel] Discord bot shut down.");
        }
    }

    /**
     * Gets the reputation manager.
     */
    public ReputationManager getReputationManager() {
        return reputationManager;
    }
}
