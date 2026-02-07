package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.context.ProfileProperty;
import world.landfall.sentinel.impersonation.ImpersonationData;
import world.landfall.sentinel.impersonation.ImpersonationManager;
import world.landfall.sentinel.impersonation.MojangApiClient;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class VelocityImpersonateCommand implements SimpleCommand {

    private final ImpersonationManager impersonationManager;
    private final MojangApiClient mojangApi;
    private final Logger logger;

    public VelocityImpersonateCommand(ImpersonationManager impersonationManager, Logger logger) {
        this.impersonationManager = impersonationManager;
        this.mojangApi = new MojangApiClient(logger);
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("This command can only be used by players", NamedTextColor.RED));
            return;
        }

        if (!impersonationManager.isUserAllowed(player.getUsername())) {
            player.sendMessage(Component.text("You are not authorized to use this command!", NamedTextColor.RED));
            player.sendMessage(Component.text("Contact an administrator to be added to the allowed users list", NamedTextColor.GRAY));
            return;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /simulacra <username|clear|reload>", NamedTextColor.YELLOW));
            return;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            impersonationManager.reloadConfig();
            player.sendMessage(Component.text("Config reloaded!", NamedTextColor.GREEN));
            logger.info("Player {} reloaded the impersonation config", player.getUsername());
            return;
        }

        if (args[0].equalsIgnoreCase("clear")) {
            impersonationManager.clearImpersonation(player.getUniqueId());
            player.sendMessage(Component.text("Cleared pending impersonation", NamedTextColor.GREEN));
            logger.info("Player {} cleared their impersonation", player.getUsername());
            return;
        }

        String targetUsername = args[0];
        player.sendMessage(Component.text("Looking up player " + targetUsername + "...", NamedTextColor.GRAY));

        CompletableFuture.runAsync(() -> {
            try {
                MojangApiClient.PlayerLookup lookup = mojangApi.lookupPlayer(targetUsername);
                if (lookup != null) {
                    List<ProfileProperty> properties = mojangApi.fetchPlayerProperties(lookup.uuid());

                    ImpersonationData data = new ImpersonationData(lookup.name(), lookup.uuid(), properties);
                    impersonationManager.addImpersonation(player.getUniqueId(), data);

                    player.sendMessage(Component.text("You will impersonate " + lookup.name() + " (UUID: " + lookup.uuid() + ") on your next login", NamedTextColor.GREEN));
                    player.sendMessage(Component.text("Please reconnect for changes to take effect", NamedTextColor.YELLOW));
                    logger.info("Player {} set up impersonation for {} (UUID: {}) with {} properties",
                            player.getUsername(), lookup.name(), lookup.uuid(), properties.size());
                } else {
                    player.sendMessage(Component.text("Could not find player: " + targetUsername, NamedTextColor.RED));
                    player.sendMessage(Component.text("Make sure the username is correct and the player exists", NamedTextColor.GRAY));
                }
            } catch (Exception e) {
                player.sendMessage(Component.text("Failed to lookup player: " + e.getMessage(), NamedTextColor.RED));
                logger.error("Failed to lookup player " + targetUsername, e);
            }
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        if (invocation.arguments().length == 0) {
            return CompletableFuture.completedFuture(List.of("clear", "reload"));
        }
        return CompletableFuture.completedFuture(List.of());
    }
}
