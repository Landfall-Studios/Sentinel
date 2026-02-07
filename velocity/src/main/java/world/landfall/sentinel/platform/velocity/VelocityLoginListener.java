package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.LoginHandler;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;

/**
 * Bridges Velocity's LoginEvent to the platform-independent LoginHandler.
 */
public class VelocityLoginListener {

    private final LoginHandler loginHandler;
    private final VelocityLoginGatekeeper gatekeeper;

    public VelocityLoginListener(LoginHandler loginHandler) {
        this.loginHandler = loginHandler;
        this.gatekeeper = new VelocityLoginGatekeeper();
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onLogin(LoginEvent event) {
        VelocityLoginContext ctx = new VelocityLoginContext(event);
        loginHandler.handleLogin(ctx, gatekeeper);
    }
}
