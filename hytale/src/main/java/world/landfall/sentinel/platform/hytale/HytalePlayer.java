package world.landfall.sentinel.platform.hytale;

import world.landfall.sentinel.context.PlatformPlayer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Wraps Hytale's PlayerRef to implement the platform-neutral PlatformPlayer interface.
 */
public class HytalePlayer implements PlatformPlayer {

    private final PlayerRef playerRef;

    public HytalePlayer(PlayerRef playerRef) {
        this.playerRef = playerRef;
    }

    @Override
    public UUID getUniqueId() {
        return playerRef.getUuid();
    }

    @Override
    public String getUsername() {
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
    public void sendMessage(String message) {
        playerRef.sendMessage(Message.raw(message));
    }

    @Override
    public void kick(String message) {
        playerRef.getPacketHandler().disconnect(message);
    }

    /**
     * Returns the underlying Hytale PlayerRef for platform-specific operations.
     */
    public PlayerRef getPlayerRef() {
        return playerRef;
    }
}
