package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.impersonation.ImpersonationManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Bridges Velocity's DisconnectEvent to clean up impersonation state.
 */
public class VelocityDisconnectListener {

    private final ImpersonationManager impersonationManager;
    private final Logger logger;

    public VelocityDisconnectListener(ImpersonationManager impersonationManager, Logger logger) {
        this.impersonationManager = impersonationManager;
        this.logger = logger;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (impersonationManager == null) {
            return;
        }

        UUID uuid = event.getPlayer().getGameProfile().getId();

        if (impersonationManager.isImpersonating(uuid)) {
            impersonationManager.removeActiveImpersonation(uuid);
            logger.debug("Cleaned up impersonation for disconnected player {}", uuid);
        }
    }
}
