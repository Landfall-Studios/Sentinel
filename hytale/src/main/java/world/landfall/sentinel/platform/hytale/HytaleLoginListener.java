package world.landfall.sentinel.platform.hytale;

import world.landfall.sentinel.LoginHandler;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;

/**
 * Bridges Hytale's PlayerConnectEvent to the platform-independent LoginHandler.
 */
public class HytaleLoginListener {

    private final LoginHandler loginHandler;
    private final HytaleLoginGatekeeper gatekeeper;

    public HytaleLoginListener(LoginHandler loginHandler) {
        this.loginHandler = loginHandler;
        this.gatekeeper = new HytaleLoginGatekeeper();
    }

    public void onPlayerConnect(PlayerConnectEvent event) {
        HytaleLoginContext ctx = new HytaleLoginContext(event.getPlayerRef());
        loginHandler.handleLogin(ctx, gatekeeper);
    }
}
