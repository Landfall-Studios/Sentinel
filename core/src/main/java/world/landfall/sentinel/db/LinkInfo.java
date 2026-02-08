package world.landfall.sentinel.db;

import world.landfall.sentinel.context.GamePlatform;
import java.util.UUID;

public record LinkInfo(UUID uuid, String discordId, String username, GamePlatform platform) { }
