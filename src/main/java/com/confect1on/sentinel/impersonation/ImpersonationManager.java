package com.confect1on.sentinel.impersonation;

import com.confect1on.sentinel.config.ConfigLoader;
import com.confect1on.sentinel.config.SentinelConfig;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.*;

public class ImpersonationManager {
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, ImpersonationData> pendingImpersonations = new HashMap<>();
    private final Map<UUID, UUID> activeImpersonations = new HashMap<>(); // Maps impersonated UUID -> original UUID
    private final Set<String> allowedUsers = new HashSet<>();
    
    public ImpersonationManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }
    
    public void setAllowedUsers(String[] users) {
        allowedUsers.clear();
        if (users != null) {
            for (String user : users) {
                if (user != null && !user.trim().isEmpty()) {
                    allowedUsers.add(user.toLowerCase());
                }
            }
        }
        logger.info("Loaded {} allowed impersonation users", allowedUsers.size());
    }
    
    public boolean isUserAllowed(String username) {
        return allowedUsers.contains(username.toLowerCase());
    }
    
    public void addImpersonation(UUID originalUuid, ImpersonationData data) {
        pendingImpersonations.put(originalUuid, data);
        logger.info("Added pending impersonation for {} -> {} ({})", 
            originalUuid, data.getUsername(), data.getUuid());
    }
    
    public ImpersonationData getImpersonation(UUID originalUuid) {
        return pendingImpersonations.get(originalUuid);
    }
    
    public ImpersonationData removeImpersonation(UUID originalUuid) {
        ImpersonationData removed = pendingImpersonations.remove(originalUuid);
        if (removed != null) {
            logger.info("Removed impersonation for {}", originalUuid);
        }
        return removed;
    }
    
    public void clearImpersonation(UUID originalUuid) {
        if (pendingImpersonations.remove(originalUuid) != null) {
            logger.info("Cleared impersonation for {}", originalUuid);
        }
    }
    
    public boolean hasImpersonation(UUID originalUuid) {
        return pendingImpersonations.containsKey(originalUuid);
    }
    
    public void setActiveImpersonation(UUID impersonatedUuid, UUID originalUuid) {
        activeImpersonations.put(impersonatedUuid, originalUuid);
        logger.info("Activated impersonation: {} -> {}", impersonatedUuid, originalUuid);
    }
    
    public UUID getOriginalUuid(UUID impersonatedUuid) {
        return activeImpersonations.get(impersonatedUuid);
    }
    
    public boolean isImpersonating(UUID uuid) {
        return activeImpersonations.containsKey(uuid);
    }
    
    public void removeActiveImpersonation(UUID impersonatedUuid) {
        UUID original = activeImpersonations.remove(impersonatedUuid);
        if (original != null) {
            logger.info("Removed active impersonation for {}", impersonatedUuid);
        }
    }
    
    public void reloadConfig() {
        try {
            SentinelConfig config = ConfigLoader.loadConfig(dataDirectory, logger);
            if (config.impersonation != null && config.impersonation.enabled) {
                setAllowedUsers(config.impersonation.allowedUsers);
                logger.info("Reloaded impersonation config with {} allowed users", allowedUsers.size());
            } else {
                allowedUsers.clear();
                logger.info("Impersonation disabled or not configured after reload");
            }
        } catch (Exception e) {
            logger.error("Failed to reload impersonation config", e);
        }
    }
}