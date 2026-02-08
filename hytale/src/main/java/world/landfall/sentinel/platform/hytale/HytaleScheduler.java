package world.landfall.sentinel.platform.hytale;

import world.landfall.sentinel.context.PlatformScheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hytale scheduler using a ScheduledExecutorService since Hytale
 * doesn't expose a plugin scheduler API.
 */
public class HytaleScheduler implements PlatformScheduler {

    private final ScheduledExecutorService executor;

    public HytaleScheduler() {
        this.executor = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "Sentinel-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void runAsync(Runnable task) {
        executor.execute(task);
    }

    @Override
    public void runRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        executor.scheduleAtFixedRate(task, delay, period, unit);
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
