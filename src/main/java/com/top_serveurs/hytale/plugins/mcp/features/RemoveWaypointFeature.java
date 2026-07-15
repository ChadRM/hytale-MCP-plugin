package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.player.RemoveMapMarker;
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

public class RemoveWaypointFeature implements McpFeature {
    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public RemoveWaypointFeature(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "remove_waypoint";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
            "remove_waypoint",
            "Removes a waypoint marker (personal or shared, previously placed with add_waypoint or by the player themselves) from a specific player's map, by its marker id.",
            "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            Map.of(
                "player", McpToolSchema.stringProperty("Player name or UUID"),
                "markerId", McpToolSchema.stringProperty("The marker's id, from add_waypoint's response or list_waypoints"),
                "world", McpToolSchema.stringProperty("World UUID")
            ),
            List.of("player", "markerId", "world")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, McpAuthManager.AuthLevel authLevel) {
        try {
            Map<String, Object> args = call.getArguments();
            if (!args.containsKey("player") || !args.containsKey("markerId") || !args.containsKey("world")) {
                return McpToolResponse.error("Missing required parameter: player, markerId, and world are all required");
            }

            String playerIdentifier = args.get("player").toString();
            String markerId = args.get("markerId").toString();
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

                    boolean existedBefore = findMarkerAnywhere(markerId, playerRef, world) != null;

                    world.getWorldMapManager().handleUserRemoveMarker(playerRef, new RemoveMapMarker(markerId));

                    boolean existsAfter = findMarkerAnywhere(markerId, playerRef, world) != null;
                    boolean changed = existedBefore && !existsAfter;

                    JsonObject json = new JsonObject();
                    json.addProperty("player", playerRef.getUsername());
                    json.addProperty("markerId", markerId);
                    json.addProperty("changed", changed);
                    if (!existedBefore) {
                        json.addProperty("note", "No marker with that id was found (already removed, or the id was wrong)");
                    } else if (!changed) {
                        json.addProperty("note", "Marker still exists after removal attempt - the player may be too far from it (removal has the same distance limit as placement)");
                    }

                    future.complete(McpToolResponse.success(GSON.toJson(json)));
                } catch (Throwable t) {
                    logger.atSevere().withCause(t).log("[REMOVE_WAYPOINT] Exception");
                    future.complete(McpToolResponse.error("Failed to remove waypoint: " + t.getMessage()));
                }
            });

            return future.join();
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error removing waypoint");
            return McpToolResponse.error("Failed to remove waypoint: " + e.getMessage());
        }
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canRemoveWaypoint();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canRemoveWaypoint();
        }
        return false;
    }

    /** Checks the player's personal markers first, then the world's shared markers - same order WorldMapManager's own (private) lookup uses. */
    private UserMapMarker findMarkerAnywhere(String markerId, PlayerRef playerRef, World world) {
        Player player = playerRef.getComponent(Player.getComponentType());
        PlayerWorldData personal = player.getPlayerConfigData().getPerWorldData(world.getName());
        UserMapMarker marker = personal.getUserMapMarker(markerId);
        if (marker != null) {
            return marker;
        }
        WorldMarkersResource shared = world.getChunkStore().getStore().getResource(WorldMarkersResource.getResourceType());
        return shared.getUserMapMarker(markerId);
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
