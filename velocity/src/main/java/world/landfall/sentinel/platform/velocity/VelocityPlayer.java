package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.context.PlatformPlayer;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.util.UUID;

public class VelocityPlayer implements PlatformPlayer {

    private final Player player;

    public VelocityPlayer(Player player) {
        this.player = player;
    }

    @Override
    public UUID getUniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String getUsername() {
        return player.getUsername();
    }

    @Override
    public String getIpAddress() {
        return player.getRemoteAddress().getAddress().getHostAddress();
    }

    @Override
    public void sendMessage(String message) {
        player.sendMessage(Component.text(message));
    }

    @Override
    public void kick(String message) {
        player.disconnect(Component.text(message));
    }

    /**
     * Returns the underlying Velocity Player for platform-specific operations.
     */
    public Player getVelocityPlayer() {
        return player;
    }
}
