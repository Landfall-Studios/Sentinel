package world.landfall.sentinel.impersonation;

import world.landfall.sentinel.context.ProfileProperty;
import java.util.List;
import java.util.UUID;

public class ImpersonationData {
    private final String username;
    private final UUID uuid;
    private final List<ProfileProperty> properties;

    public ImpersonationData(String username, UUID uuid, List<ProfileProperty> properties) {
        this.username = username;
        this.uuid = uuid;
        this.properties = properties;
    }

    public String getUsername() { return username; }
    public UUID getUuid() { return uuid; }
    public List<ProfileProperty> getProperties() { return properties; }
}
