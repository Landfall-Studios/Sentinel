package world.landfall.sentinel.context;

import java.util.UUID;

public interface PlatformPlayer {
    UUID getUniqueId();
    String getUsername();
    String getIpAddress();
    void sendMessage(String message);
    void kick(String message);
}
