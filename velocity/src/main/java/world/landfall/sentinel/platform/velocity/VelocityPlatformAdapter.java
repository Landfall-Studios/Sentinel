package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.context.PlatformAdapter;
import world.landfall.sentinel.context.PlatformPlayer;
import world.landfall.sentinel.context.PlatformScheduler;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public class VelocityPlatformAdapter implements PlatformAdapter {

    private final ProxyServer server;
    private final Path dataDirectory;
    private final VelocityScheduler scheduler;

    public VelocityPlatformAdapter(ProxyServer server, Path dataDirectory) {
        this.server = server;
        this.dataDirectory = dataDirectory;
        this.scheduler = new VelocityScheduler(server);
    }

    @Override
    public Optional<PlatformPlayer> getPlayer(UUID uuid) {
        return server.getPlayer(uuid).map(VelocityPlayer::new);
    }

    @Override
    public void kickPlayer(UUID uuid, String message) {
        Optional<Player> player = server.getPlayer(uuid);
        player.ifPresent(p -> p.disconnect(Component.text(message)));
    }

    @Override
    public Path getDataDirectory() {
        return dataDirectory;
    }

    @Override
    public PlatformScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Returns the underlying ProxyServer for Velocity-specific operations.
     */
    public ProxyServer getProxyServer() {
        return server;
    }
}
