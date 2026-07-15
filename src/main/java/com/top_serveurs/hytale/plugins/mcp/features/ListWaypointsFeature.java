package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.user.UserMapMarker;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.worldstore.WorldMarkersResource;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ListWaypointsFeature implements McpFeature {
    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public ListWaypointsFeature(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "list_waypoints";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
            "list_waypoints",
            "Lists the waypoint markers currently on a specific player's world map in a world - their personal markers plus any shared markers on that world.",
            "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            Map.of(
                "player", McpToolSchema.stringProperty("Player name or UUID"),
                "world", McpToolSchema.stringProperty("World UUID")
            ),
            List.of("player", "world")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, McpAuthManager.AuthLevel authLevel) {
        try {
            Map<String, Object> args = call.getArguments();
            if (!args.containsKey("player") || !args.containsKey("world")) {
                return McpToolResponse.error("Missing required parameter: player and world are both required");
            }

            String playerIdentifier = args.get("player").toString();
            String worldUuidStr = args.get("world").toString();

            UUID worldUuid;
            try {
                worldUuid = UUID.fromString(worldUuidStr);
            } catch (IllegalArgumentException e) {
                return McpToolResponse.error("Invalid world UUID");
            }

            World world = Universe.get().getWorld(worldUuid);
            if (world == null) {
                return McpToolResponse.error("World not found: " + worldUuidStr);
            }

            CompletableFuture<McpToolResponse> future = new CompletableFuture<>();

            world.execute(() -> {
                try {
                    PlayerRef playerRef = findPlayer(playerIdentifier);
                    if (playerRef == null) {
                        future.complete(McpToolResponse.error("Player not found: " + playerIdentifier));
                        return;
                    }

                    Player player = playerRef.getComponent(Player.getComponentType());
                    PlayerWorldData personal = player.getPlayerConfigData().getPerWorldData(world.getName());
                    WorldMarkersResource shared = world.getChunkStore().getStore()
                            .getResource(WorldMarkersResource.getResourceType());

                    JsonArray markers = new JsonArray();
                    addMarkers(markers, personal.getUserMapMarkers(), false);
                    addMarkers(markers, shared.getUserMapMarkers(), true);

                    JsonObject json = new JsonObject();
                    json.addProperty("player", playerRef.getUsername());
                    json.add("markers", markers);
                    json.addProperty("count", markers.size());

                    future.complete(McpToolResponse.success(GSON.toJson(json)));
                } catch (Throwable t) {
                    logger.atSevere().withCause(t).log("[LIST_WAYPOINTS] Exception");
                    future.complete(McpToolResponse.error("Failed to list waypoints: " + t.getMessage()));
                }
            });

            return future.join();
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error listing waypoints");
            return McpToolResponse.error("Failed to list waypoints: " + e.getMessage());
        }
    }

    private void addMarkers(JsonArray out, Collection<? extends UserMapMarker> markers, boolean shared) {
        for (UserMapMarker m : markers) {
            JsonObject obj = new JsonObject();
            obj.addProperty("markerId", m.getId());
            obj.addProperty("name", m.getName());
            obj.addProperty("x", m.getX());
            obj.addProperty("z", m.getZ());
            obj.addProperty("icon", m.getIcon());
            obj.addProperty("shared", shared);
            obj.addProperty("createdByName", m.getCreatedByName());
            Color tint = m.getColorTint();
            if (tint != null) {
                obj.addProperty("colorHex", String.format("#%02x%02x%02x",
                        tint.red & 0xFF, tint.green & 0xFF, tint.blue & 0xFF));
            }
            out.add(obj);
        }
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canListWaypoints();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canListWaypoints();
        }
        return false;
    }

    private PlayerRef findPlayer(String identifier) {
        Collection<PlayerRef> players = Universe.get().getPlayers();

        try {
            UUID uuid = UUID.fromString(identifier);
            for (PlayerRef player : players) {
                if (player.getUuid().equals(uuid)) {
                    return player;
                }
            }
        } catch (IllegalArgumentException e) {
        }

        for (PlayerRef player : players) {
            if (player.getUsername().equalsIgnoreCase(identifier)) {
                return player;
            }
        }

        return null;
    }
}
