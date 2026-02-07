package world.landfall.sentinel.platform.velocity;

import world.landfall.sentinel.context.ProfileProperty;
import world.landfall.sentinel.impersonation.ImpersonationData;
import world.landfall.sentinel.impersonation.ImpersonationManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bridges Velocity's GameProfileRequestEvent to handle impersonation.
 * Converts between core ProfileProperty and Velocity GameProfile.Property.
 */
public class VelocityGameProfileListener {

    private final ImpersonationManager impersonationManager;
    private final Logger logger;

    public VelocityGameProfileListener(ImpersonationManager impersonationManager, Logger logger) {
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

            // Convert core ProfileProperty list to Velocity GameProfile.Property list
            List<GameProfile.Property> velocityProperties;
            if (impersonation.getProperties() != null && !impersonation.getProperties().isEmpty()) {
                velocityProperties = impersonation.getProperties().stream()
                        .map(p -> new GameProfile.Property(p.name(), p.value(), p.signature()))
                        .collect(Collectors.toList());
            } else {
                velocityProperties = original.getProperties();
            }

            GameProfile impersonatedProfile = new GameProfile(
                    impersonation.getUuid(),
                    impersonation.getUsername(),
                    velocityProperties
            );

            event.setGameProfile(impersonatedProfile);

            impersonationManager.removeImpersonation(playerUuid);
        }
    }
}
