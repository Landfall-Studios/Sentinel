package world.landfall.sentinel.context;

import java.util.Optional;
import java.util.UUID;

public interface LoginContext {
    UUID getPlayerUuid();
    String getPlayerUsername();
    String getIpAddress();
    Optional<String> getVirtualHost();
    GamePlatform getPlatform();
}
