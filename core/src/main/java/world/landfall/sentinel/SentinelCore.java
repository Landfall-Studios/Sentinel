package world.landfall.sentinel;

import world.landfall.sentinel.context.PlatformAdapter;
import org.slf4j.Logger;

/**
 * Service locator for cross-platform access to platform-specific services.
 * Initialized once by the platform entry point (e.g. VelocitySentinel).
 */
public final class SentinelCore {

    private static PlatformAdapter platform;
    private static Logger logger;

    private SentinelCore() {}

    public static void init(PlatformAdapter platform, Logger logger) {
        SentinelCore.platform = platform;
        SentinelCore.logger = logger;
    }

    public static PlatformAdapter platform() {
        if (platform == null) throw new IllegalStateException("SentinelCore not initialized");
        return platform;
    }

    public static Logger logger() {
        if (logger == null) throw new IllegalStateException("SentinelCore not initialized");
        return logger;
    }
}
