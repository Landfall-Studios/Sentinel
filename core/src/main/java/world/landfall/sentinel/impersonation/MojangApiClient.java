package world.landfall.sentinel.impersonation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import world.landfall.sentinel.context.ProfileProperty;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles HTTP lookups against the Mojang API for player profiles.
 * Extracted from the Velocity-specific ImpersonateCommand to be platform-independent.
 */
public class MojangApiClient {

    private final Logger logger;

    public MojangApiClient(Logger logger) {
        this.logger = logger;
    }

    /**
     * Looks up a Minecraft player by username via the Mojang API.
     *
     * @param username The Minecraft username to look up
     * @return The lookup result, or null if not found
     */
    public PlayerLookup lookupPlayer(String username) {
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + username);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (connection.getResponseCode() == 200) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(connection.getInputStream())).getAsJsonObject();
                String uuidStr = json.get("id").getAsString();
                String name = json.get("name").getAsString();

                UUID targetUuid = UUID.fromString(
                        uuidStr.substring(0, 8) + "-" +
                        uuidStr.substring(8, 12) + "-" +
                        uuidStr.substring(12, 16) + "-" +
                        uuidStr.substring(16, 20) + "-" +
                        uuidStr.substring(20, 32)
                );

                connection.disconnect();
                return new PlayerLookup(name, targetUuid);
            }

            connection.disconnect();
        } catch (Exception e) {
            logger.error("Failed to lookup player " + username, e);
        }
        return null;
    }

    /**
     * Fetches the skin/cape properties for a player UUID from the Mojang session server.
     *
     * @param uuid The player UUID
     * @return List of profile properties (textures, etc.)
     */
    public List<ProfileProperty> fetchPlayerProperties(UUID uuid) {
        List<ProfileProperty> properties = new ArrayList<>();
        try {
            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" +
                    uuid.toString().replace("-", "") + "?unsigned=false");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (connection.getResponseCode() == 200) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(connection.getInputStream())).getAsJsonObject();

                if (json.has("properties")) {
                    JsonArray propertiesArray = json.getAsJsonArray("properties");
                    for (JsonElement element : propertiesArray) {
                        JsonObject property = element.getAsJsonObject();
                        String propertyName = property.get("name").getAsString();
                        String value = property.get("value").getAsString();
                        String signature = property.has("signature") ? property.get("signature").getAsString() : null;

                        properties.add(new ProfileProperty(propertyName, value, signature));
                    }
                }
            }
            connection.disconnect();
        } catch (Exception e) {
            logger.error("Failed to fetch player properties for UUID " + uuid, e);
        }
        return properties;
    }

    /**
     * Result of a Mojang player lookup.
     */
    public record PlayerLookup(String name, UUID uuid) {}
}
