package org.antigravity.autofight.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class SkinManager {
    private final Logger logger;
    private final HttpClient httpClient;
    private final Map<String, Property> skinCache = new ConcurrentHashMap<>();

    public SkinManager(Logger logger) {
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public CompletableFuture<Property> fetchSkin(String username) {
        String key = username.toLowerCase();
        Property cached = skinCache.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Get UUID from Mojang API
                HttpRequest uuidReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> uuidRes = httpClient.send(uuidReq, HttpResponse.BodyHandlers.ofString());
                if (uuidRes.statusCode() != 200 || uuidRes.body().isBlank()) {
                    logger.warning("Could not find Mojang profile for user: " + username + " (Status: " + uuidRes.statusCode() + ")");
                    return null;
                }

                JsonObject uuidJson = JsonParser.parseString(uuidRes.body()).getAsJsonObject();
                String rawUuid = uuidJson.get("id").getAsString();

                // 2. Get Profile properties from Session Server
                HttpRequest profileReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + rawUuid + "?unsigned=false"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> profileRes = httpClient.send(profileReq, HttpResponse.BodyHandlers.ofString());
                if (profileRes.statusCode() != 200 || profileRes.body().isBlank()) {
                    logger.warning("Could not fetch skin properties for UUID: " + rawUuid);
                    return null;
                }

                JsonObject profileJson = JsonParser.parseString(profileRes.body()).getAsJsonObject();
                JsonArray properties = profileJson.getAsJsonArray("properties");
                if (properties != null) {
                    for (JsonElement elem : properties) {
                        JsonObject prop = elem.getAsJsonObject();
                        if ("textures".equals(prop.get("name").getAsString())) {
                            String value = prop.get("value").getAsString();
                            String signature = prop.has("signature") ? prop.get("signature").getAsString() : null;
                            Property skinProperty = new Property("textures", value, signature);
                            skinCache.put(key, skinProperty);
                            return skinProperty;
                        }
                    }
                }
            } catch (Exception e) {
                logger.warning("Failed to fetch skin for " + username + ": " + e.getMessage());
            }
            return null;
        });
    }

    public void cacheSkin(String username, Property property) {
        if (username != null && property != null) {
            skinCache.put(username.toLowerCase(), property);
        }
    }
}
