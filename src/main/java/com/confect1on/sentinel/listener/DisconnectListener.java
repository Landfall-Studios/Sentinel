package com.confect1on.sentinel.listener;

import com.confect1on.sentinel.impersonation.ImpersonationManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.slf4j.Logger;

import java.util.UUID;

public class DisconnectListener {
    
    private final ImpersonationManager impersonationManager;
    private final Logger logger;
    
    public DisconnectListener(ImpersonationManager impersonationManager, Logger logger) {
        this.impersonationManager = impersonationManager;
        this.logger = logger;
    }
    
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (impersonationManager == null) {
            return;
        }
        
        UUID uuid = event.getPlayer().getGameProfile().getId();
        
        // Clean up active impersonation when player disconnects
        if (impersonationManager.isImpersonating(uuid)) {
            impersonationManager.removeActiveImpersonation(uuid);
            logger.debug("Cleaned up impersonation for disconnected player {}", uuid);
        }
    }
}