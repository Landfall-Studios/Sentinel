package world.landfall.sentinel.context;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public interface PlatformAdapter {
    Optional<PlatformPlayer> getPlayer(UUID uuid);
    void kickPlayer(UUID uuid, String message);
    Path getDataDirectory();
    PlatformScheduler getScheduler();
}
