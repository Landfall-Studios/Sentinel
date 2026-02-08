package world.landfall.sentinel.platform.hytale;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import org.slf4j.Logger;

/**
 * Bridges Hytale's PlayerDisconnectEvent. Placeholder for disconnect handling.
 * No impersonation cleanup needed on Hytale (feature not supported).
 */
public class HytaleDisconnectListener {

    private final Logger logger;

    public HytaleDisconnectListener(Logger logger) {
        this.logger = logger;
    }

    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        logger.debug("Player {} disconnected", event.getPlayerRef().getUsername());
    }
}
