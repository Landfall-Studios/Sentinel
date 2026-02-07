package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.context.PlatformScheduler;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.concurrent.TimeUnit;

public class VelocityScheduler implements PlatformScheduler {

    private final ProxyServer server;

    public VelocityScheduler(ProxyServer server) {
        this.server = server;
    }

    @Override
    public void runAsync(Runnable task) {
        server.getScheduler().buildTask(server, task).schedule();
    }

    @Override
    public void runRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        server.getScheduler().buildTask(server, task)
                .delay(delay, unit)
                .repeat(period, unit)
                .schedule();
    }

    @Override
    public void shutdown() {
        // Velocity manages its own scheduler lifecycle
    }
}
