package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.packets.worldmap.CreateUserMarker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.user.UserMapMarker;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.user.UserMapMarkersStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.user.UserMarkerValidator;
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

public class AddWaypointFeature implements McpFeature {
    private static final Gson GSON = new Gson();
    // The engine's internal null-icon fallback ("User1.png") is a broken asset that doesn't render
    // on the map screen - see the comment where this constant is used.
    private static final String DEFAULT_ICON = "UserA.png";
    private final HytaleLogger logger;

    public AddWaypointFeature(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "add_waypoint";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
            "add_waypoint",
            "Places a waypoint marker on a specific player's in-game world map (the 'M' key map) at the given world x/z coordinates. Personal by default (only that player sees it); set shared=true to make it visible to everyone. Returns the marker's id, needed later by remove_waypoint. Placement has a distance limit tied to the player's view radius - it must land within their currently visible range, not anywhere on the whole map.",
            "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            Map.of(
                "player", McpToolSchema.stringProperty("Player name or UUID"),
                "x", McpToolSchema.integerProperty("World X coordinate"),
                "z", McpToolSchema.integerProperty("World Z coordinate"),
                "name", McpToolSchema.stringProperty("Marker label shown on the map (max 24 characters)"),
                "world", McpToolSchema.stringProperty("World UUID"),
                "icon", McpToolSchema.stringProperty("Optional marker icon filename. Defaults to 'UserA.png' (the icon the in-game Quick Marker button uses) if omitted - NOT the engine's own internal default ('User1.png'), which is a broken/missing icon asset that silently fails to render on the map screen (confirmed live 2026-07-15)."),
                "colorHex", McpToolSchema.stringProperty("Optional tint color as a hex string, e.g. '#ffcc00'. Defaults to no tint."),
                "shared", McpToolSchema.booleanProperty("Optional - true makes the marker visible to all players, not just this one. Defaults to false.")
            ),
            List.of("player", "x", "z", "name", "world")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, McpAuthManager.AuthLevel authLevel) {
        try {
            Map<String, Object> args = call.getArguments();
            if (!args.containsKey("player") || !args.containsKey("x") || !args.containsKey("z")
                    || !args.containsKey("name") || !args.containsKey("world")) {
                return McpToolResponse.error("Missing required parameter: player, x, z, name, and world are all required");
            }

            String playerIdentifier = args.get("player").toString();
            float x = ((Number) args.get("x")).floatValue();
            float z = ((Number) args.get("z")).floatValue();
            String name = args.get("name").toString();
            String worldUuidStr = args.get("world").toString();
            // The engine's own internal fallback for a null markerImage is "User1.png", which is a
            // broken/missing icon asset in this client build: it still shows on the compass (as a
            // generic broken-image glyph) but the map screen silently drops the marker entirely.
            // Default to "UserA.png" (the icon the real in-game Quick Marker button sends) instead,
            // confirmed live to render correctly on both the compass and the map.
            String iconArg = args.containsKey("icon") && args.get("icon") != null
                    ? args.get("icon").toString()
                    : DEFAULT_ICON;
            String colorHexArg = args.containsKey("colorHex") && args.get("colorHex") != null ? args.get("colorHex").toString() : null;
            boolean shared = args.containsKey("shared") && Boolean.parseBoolean(args.get("shared").toString());

            Color tintColor = null;
            if (colorHexArg != null && !colorHexArg.isBlank()) {
                tintColor = parseColor(colorHexArg);
                if (tintColor == null) {
                    return McpToolResponse.error("Invalid colorHex: " + colorHexArg + " (expected a hex string like #ffcc00)");
                }
            }
            final Color finalTintColor = tintColor;

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

                    Ref<EntityStore> ref = playerRef.getReference();
                    if (ref == null) {
                        future.complete(McpToolResponse.error("Player entity not currently loaded: " + playerIdentifier));
                        return;
                    }

                    CreateUserMarker packet = new CreateUserMarker(x, z, name, iconArg, finalTintColor, shared);

                    UserMarkerValidator.PlaceResult result = UserMarkerValidator.validatePlacing(ref, packet);
                    if (result instanceof UserMarkerValidator.Fail fail) {
                        future.complete(McpToolResponse.error("Marker placement rejected: " + fail.errorMsg().getRawText()));
                        return;
                    }

                    UserMapMarkersStore store = ((UserMarkerValidator.CanSpawn) result).markersStore();

                    // The real client-triggered code path - does its own (redundant but cheap)
                    // validation, generates the marker id, and stores it. Using this instead of
                    // writing the store directly so waypoints behave exactly like a player-placed
                    // pin, including whatever client sync the engine does on this path.
                    world.getWorldMapManager().handleUserCreateMarker(playerRef, packet);

                    String markerId = null;
                    for (UserMapMarker m : store.getUserMapMarkers()) {
                        if (m.getX() == x && m.getZ() == z && name.equals(m.getName())) {
                            markerId = m.getId();
                        }
                    }

                    JsonObject json = new JsonObject();
                    json.addProperty("player", playerRef.getUsername());
                    json.addProperty("x", x);
                    json.addProperty("z", z);
                    json.addProperty("name", name);
                    json.addProperty("shared", shared);
                    if (markerId != null) {
                        json.addProperty("markerId", markerId);
                    } else {
                        json.addProperty("warning", "Marker was placed but its id could not be confirmed by re-reading the store");
                    }

                    future.complete(McpToolResponse.success(GSON.toJson(json)));
                } catch (Throwable t) {
                    logger.atSevere().withCause(t).log("[ADD_WAYPOINT] Exception");
                    future.complete(McpToolResponse.error("Failed to add waypoint: " + t.getMessage()));
                }
            });

            return future.join();
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error adding waypoint");
            return McpToolResponse.error("Failed to add waypoint: " + e.getMessage());
        }
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canAddWaypoint();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canAddWaypoint();
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

    private static Color parseColor(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) {
            return null;
        }
        try {
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            return new Color((byte) r, (byte) g, (byte) b);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
