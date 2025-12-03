package com.confect1on.sentinel.listener;

import com.confect1on.sentinel.impersonation.ImpersonationData;
import com.confect1on.sentinel.impersonation.ImpersonationManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.util.UUID;

public class GameProfileListener {
    
    private final ImpersonationManager impersonationManager;
    private final Logger logger;
    
    public GameProfileListener(ImpersonationManager impersonationManager, Logger logger) {
        this.impersonationManager = impersonationManager;
        this.logger = logger;
    }
    
    @Subscribe(order = PostOrder.EARLY)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        if (!event.isOnlineMode()) {
            return;
        }
        
        GameProfile original = event.getGameProfile();
        UUID playerUuid = original.getId();
        
        ImpersonationData impersonation = impersonationManager.getImpersonation(playerUuid);
        if (impersonation != null) {
            logger.info("Player {} is impersonating {} with UUID {} (running EARLY before other plugins)", 
                original.getName(), impersonation.getUsername(), impersonation.getUuid());
            
            // Track the active impersonation for later lookup
            impersonationManager.setActiveImpersonation(impersonation.getUuid(), playerUuid);
            
            // Use the fetched properties (skin/cape) instead of original player's properties
            GameProfile impersonatedProfile = new GameProfile(
                impersonation.getUuid(),
                impersonation.getUsername(),
                impersonation.getProperties() != null && !impersonation.getProperties().isEmpty() 
                    ? impersonation.getProperties() 
                    : original.getProperties()
            );
            
            event.setGameProfile(impersonatedProfile);
            
            impersonationManager.removeImpersonation(playerUuid);
        }
    }
}