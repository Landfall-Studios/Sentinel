package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.context.GamePlatform;
import world.landfall.sentinel.context.LoginContext;
import com.velocitypowered.api.event.connection.LoginEvent;

import java.util.Optional;
import java.util.UUID;

public class VelocityLoginContext implements LoginContext {

    private final LoginEvent event;

    public VelocityLoginContext(LoginEvent event) {
        this.event = event;
    }

    @Override
    public UUID getPlayerUuid() {
        return event.getPlayer().getGameProfile().getId();
    }

    @Override
    public String getPlayerUsername() {
        return event.getPlayer().getUsername();
    }

    @Override
    public String getIpAddress() {
        return event.getPlayer().getRemoteAddress().getAddress().getHostAddress();
    }

    @Override
    public Optional<String> getVirtualHost() {
        return event.getPlayer().getVirtualHost()
                .map(host -> host.getHostString());
    }

    @Override
    public GamePlatform getPlatform() {
        return GamePlatform.MINECRAFT;
    }

    /**
     * Returns the underlying Velocity LoginEvent for platform-specific operations.
     */
    public LoginEvent getEvent() {
        return event;
    }
}
