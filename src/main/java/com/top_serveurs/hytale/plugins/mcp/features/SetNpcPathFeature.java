package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.builtin.path.path.TransientPath;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Assigns a real, absolute-coordinate patrol path to the NPC nearest a search position. The two
 * built-in "/npc path" commands (set/polygon) only accept relative turn+distance instructions or a
 * regular polygon - neither takes literal world waypoints, which is what's actually needed to walk a
 * road already planned in absolute coordinates. TransientPath.addWaypoint(Vector3d, Rotation3f)
 * takes an absolute position directly, so this bypasses the command's relative-instruction parsing
 * entirely and builds the path straight from real coordinates.
 */
public class SetNpcPathFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private static final double DEFAULT_SEARCH_RADIUS = 10.0;
    private final HytaleLogger logger;

    public SetNpcPathFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "set_npc_path";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "set_npc_path",
                "Assigns a patrol path (a list of absolute {x,y,z} waypoints) to the NPC nearest a search "
                        + "position - either an explicit x/y/z or near a named player. The NPC must already be "
                        + "spawned (see spawn_npc). Needs at least 2 waypoints. This only sets the route; whether "
                        + "the NPC's role actually follows it (vs. wandering/idling) depends on its role - "
                        + "*_Patrol roles are designed to follow an assigned path.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        var waypointSchema = McpToolSchema.objectProperty(
            java.util.Map.of(
                "x", McpToolSchema.stringProperty("X coordinate"),
                "y", McpToolSchema.stringProperty("Y coordinate"),
                "z", McpToolSchema.stringProperty("Z coordinate")
            ),
            java.util.List.of("x", "y", "z"),
            "An absolute waypoint on the patrol path"
        );

        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "world", McpToolSchema.stringProperty("World UUID"),
                "waypoints", McpToolSchema.arrayProperty(waypointSchema, "Ordered list of 2+ absolute waypoints the NPC will walk"),
                "player", McpToolSchema.stringProperty("Player name or UUID to search near for the target NPC. Required if x/y/z are omitted."),
                "x", McpToolSchema.stringProperty("Explicit X coordinate to search near for the target NPC."),
                "y", McpToolSchema.stringProperty("Explicit Y coordinate."),
                "z", McpToolSchema.stringProperty("Explicit Z coordinate."),
                "radius", McpToolSchema.stringProperty("Search radius in blocks for finding the target NPC. Defaults to " + DEFAULT_SEARCH_RADIUS + ".")
            ),
            java.util.List.of("world", "waypoints")
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        String worldUuidStr = getArgumentAsString(call, "world");
        String playerIdentifier = getArgumentAsString(call, "player");
        Double explicitX = getArgumentAsDouble(call, "x");
        Double explicitY = getArgumentAsDouble(call, "y");
        Double explicitZ = getArgumentAsDouble(call, "z");
        double radius = getArgumentAsDoubleOrDefault(call, "radius", DEFAULT_SEARCH_RADIUS);
        Object waypointsObj = call.getArguments().get("waypoints");

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }
        if (waypointsObj == null) {
            return McpToolResponse.error("waypoints is required");
        }

        List<Vector3d> waypoints = new ArrayList<>();
        try {
            for (Object entryObj : (List<Object>) waypointsObj) {
                var entry = (java.util.Map<String, Object>) entryObj;
                double x = Double.parseDouble(entry.get("x").toString());
                double y = Double.parseDouble(entry.get("y").toString());
                double z = Double.parseDouble(entry.get("z").toString());
                waypoints.add(new Vector3d(x, y, z));
            }
        } catch (Exception e) {
            return McpToolResponse.error("Invalid waypoints format: " + e.getMessage());
        }

        if (waypoints.size() < 2) {
            return McpToolResponse.error("At least 2 waypoints are required");
        }

        boolean hasExplicitPosition = explicitX != null && explicitY != null && explicitZ != null;
        if (!hasExplicitPosition && playerIdentifier == null) {
            return McpToolResponse.error("Either player, or all of x/y/z, must be provided to locate the target NPC");
        }

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
                Vector3d searchCenter;

                if (hasExplicitPosition) {
                    searchCenter = new Vector3d(explicitX, explicitY, explicitZ);
                } else {
                    PlayerRef player = findPlayer(playerIdentifier);
                    if (player == null) {
                        future.complete(McpToolResponse.error("Player not found: " + playerIdentifier));
                        return;
                    }
                    searchCenter = player.getTransform().getPosition();
                }

                Store<EntityStore> store = world.getEntityStore().getStore();

                Ref<EntityStore>[] nearestRefHolder = new Ref[1];
                String[] nearestTypeIdHolder = new String[1];
                double[] nearestDistanceHolder = { Double.MAX_VALUE };
                Vector3d finalSearchCenter = searchCenter;

                store.forEachChunk(NPCEntity.getComponentType(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> cmdBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (transform == null) continue;
                        double distance = transform.getPosition().distance(finalSearchCenter);
                        if (distance <= radius && distance < nearestDistanceHolder[0]) {
                            NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                            nearestRefHolder[0] = chunk.getReferenceTo(i);
                            nearestTypeIdHolder[0] = npc != null ? npc.getNPCTypeId() : "unknown";
                            nearestDistanceHolder[0] = distance;
                        }
                    }
                });

                if (nearestRefHolder[0] == null) {
                    future.complete(McpToolResponse.error("No NPC found within " + radius + " blocks of the search position"));
                    return;
                }

                NPCEntity targetNpc = store.getComponent(nearestRefHolder[0], NPCEntity.getComponentType());
                if (targetNpc == null) {
                    future.complete(McpToolResponse.error("Found a nearby NPC reference but it no longer resolves - it may have despawned"));
                    return;
                }

                TransientPath path = new TransientPath();
                for (int i = 0; i < waypoints.size(); i++) {
                    Vector3d point = waypoints.get(i);
                    Vector3d directionSource = (i < waypoints.size() - 1) ? waypoints.get(i + 1) : waypoints.get(i - 1);
                    Vector3d direction = new Vector3d(directionSource).sub(point);
                    if (i == waypoints.size() - 1) {
                        direction.negate();
                    }
                    Rotation3f facing = direction.lengthSquared() > 0
                            ? Rotation3f.lookAt(direction)
                            : new Rotation3f();
                    path.addWaypoint(point, facing);
                }

                targetNpc.getPathManager().setTransientPath(path);

                JsonArray waypointsJson = new JsonArray();
                for (Vector3d wp : waypoints) {
                    JsonObject wpJson = new JsonObject();
                    wpJson.addProperty("x", wp.x());
                    wpJson.addProperty("y", wp.y());
                    wpJson.addProperty("z", wp.z());
                    waypointsJson.add(wpJson);
                }

                JsonObject response = new JsonObject();
                response.addProperty("npcTypeId", nearestTypeIdHolder[0]);
                response.addProperty("distanceFromSearchCenter", nearestDistanceHolder[0]);
                response.addProperty("waypointCount", waypoints.size());
                response.add("waypoints", waypointsJson);

                logger.atInfo().log("[SET_NPC_PATH] Assigned " + waypoints.size() + "-waypoint path to "
                        + nearestTypeIdHolder[0]);

                future.complete(McpToolResponse.success(GSON.toJson(response)));
            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[SET_NPC_PATH] Exception");
                future.complete(McpToolResponse.error("Failed to set NPC path: " + t.getMessage()));
            }
        });

        return future.join();
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

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canSpawnNpc();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canSpawnNpc();
        }
        return false;
    }

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }

    private Double getArgumentAsDouble(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double getArgumentAsDoubleOrDefault(McpToolCall call, String key, double defaultValue) {
        Double value = getArgumentAsDouble(call, key);
        return value != null ? value : defaultValue;
    }
}
