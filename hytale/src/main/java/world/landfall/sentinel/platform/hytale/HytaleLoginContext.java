package world.landfall.sentinel.platform.hytale;

import world.landfall.sentinel.context.GamePlatform;
import world.landfall.sentinel.context.LoginContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

/**
 * Hytale implementation of LoginContext, wrapping a PlayerRef from PlayerConnectEvent.
 * Virtual host is always empty — Hytale game servers don't expose SRV/virtual host data.
 */
public class HytaleLoginContext implements LoginContext {

    private final PlayerRef playerRef;

    public HytaleLoginContext(PlayerRef playerRef) {
        this.playerRef = playerRef;
    }

    @Override
    public UUID getPlayerUuid() {
        return playerRef.getUuid();
    }

    @Override
    public String getPlayerUsername() {
        return playerRef.getUsername();
    }

    @Override
    public String getIpAddress() {
        var remoteAddress = playerRef.getPacketHandler().getChannel().remoteAddress();
        if (remoteAddress instanceof InetSocketAddress inet) {
            return inet.getAddress().getHostAddress();
        }
        return "unknown";
    }

    @Override
    public Optional<String> getVirtualHost() {
        return Optional.empty();
    }

    @Override
    public GamePlatform getPlatform() {
        return GamePlatform.HYTALE;
    }

    /**
     * Returns the underlying Hytale PlayerRef for platform-specific operations.
     */
    public PlayerRef getPlayerRef() {
        return playerRef;
    }
}
