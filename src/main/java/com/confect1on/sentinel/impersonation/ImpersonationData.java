package com.confect1on.sentinel.impersonation;

import com.velocitypowered.api.util.GameProfile;
import java.util.List;
import java.util.UUID;

public class ImpersonationData {
    private final String username;
    private final UUID uuid;
    private final List<GameProfile.Property> properties;
    
    public ImpersonationData(String username, UUID uuid, List<GameProfile.Property> properties) {
        this.username = username;
        this.uuid = uuid;
        this.properties = properties;
    }
    
    public String getUsername() {
        return username;
    }
    
    public UUID getUuid() {
        return uuid;
    }
    
    public List<GameProfile.Property> getProperties() {
        return properties;
    }
}