package world.landfall.sentinel.platform.hytale;

import world.landfall.sentinel.context.PlatformAdapter;
import world.landfall.sentinel.context.PlatformPlayer;
import world.landfall.sentinel.context.PlatformScheduler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Hytale implementation of PlatformAdapter.
 * Uses Universe.get().getPlayer(UUID) for direct lookup.
 */
public class HytalePlatformAdapter implements PlatformAdapter {

    private final Path dataDirectory;
    private final HytaleScheduler scheduler;

    public HytalePlatformAdapter(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.scheduler = new HytaleScheduler();
    }

    @Override
    public Optional<PlatformPlayer> getPlayer(UUID uuid) {
        PlayerRef playerRef = Universe.get().getPlayer(uuid);
        if (playerRef != null) {
            return Optional.of(new HytalePlayer(playerRef));
        }
        return Optional.empty();
    }

    @Override
    public void kickPlayer(UUID uuid, String message) {
        getPlayer(uuid).ifPresent(p -> p.kick(message));
    }

    @Override
    public Path getDataDirectory() {
        return dataDirectory;
    }

    @Override
    public PlatformScheduler getScheduler() {
        return scheduler;
    }
}
