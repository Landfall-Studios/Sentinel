package world.landfall.sentinel.context;

import java.util.concurrent.TimeUnit;

public interface PlatformScheduler {
    void runAsync(Runnable task);
    void runRepeating(Runnable task, long delay, long period, TimeUnit unit);
    void shutdown();
}
