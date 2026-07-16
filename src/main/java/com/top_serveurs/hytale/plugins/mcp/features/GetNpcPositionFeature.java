package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
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

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reads the live position/rotation of the NPC nearest a search position (either an explicit x/y/z
 * or near a named player). Same "nearest within radius" search as despawn_npc/set_npc_path - there's
 * no per-NPC handle returned by spawn_npc to target directly (yet). Read-only, no world mutation:
 * lets a caller poll an NPC's position over time to verify movement/behavior without needing someone
 * watching in-game.
 */
public class GetNpcPositionFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private static final double DEFAULT_RADIUS = 10.0;
    private final HytaleLogger logger;

    public GetNpcPositionFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "get_npc_position";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "get_npc_position",
                "Gets the live position, rotation, and role of the NPC nearest a search position - either "
                        + "an explicit x/y/z or near a named player. Poll this repeatedly to verify an NPC is "
                        + "actually moving/behaving as expected without needing to watch in-game.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "world", McpToolSchema.stringProperty("World UUID"),
                "player", McpToolSchema.stringProperty("Player name or UUID to search near. Required if x/y/z are omitted."),
                "x", McpToolSchema.stringProperty("Explicit X coordinate to search near. Required together with y/z if player is omitted."),
                "y", McpToolSchema.stringProperty("Explicit Y coordinate."),
                "z", McpToolSchema.stringProperty("Explicit Z coordinate."),
                "radius", McpToolSchema.stringProperty("Search radius in blocks. Defaults to " + DEFAULT_RADIUS + ".")
            ),
            java.util.List.of("world")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        String worldUuidStr = getArgumentAsString(call, "world");
        String playerIdentifier = getArgumentAsString(call, "player");
        Double explicitX = getArgumentAsDouble(call, "x");
        Double explicitY = getArgumentAsDouble(call, "y");
        Double explicitZ = getArgumentAsDouble(call, "z");
        double radius = getArgumentAsDoubleOrDefault(call, "radius", DEFAULT_RADIUS);

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }

        boolean hasExplicitPosition = explicitX != null && explicitY != null && explicitZ != null;
        if (!hasExplicitPosition && playerIdentifier == null) {
            return McpToolResponse.error("Either player, or all of x/y/z, must be provided");
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
                Vector3d finalSearchCenter = searchCenter;

                TransformComponent[] nearestTransformHolder = new TransformComponent[1];
                String[] nearestTypeIdHolder = new String[1];
                double[] nearestDistanceHolder = { Double.MAX_VALUE };

                store.forEachChunk(NPCEntity.getComponentType(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> cmdBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (transform == null) continue;
                        double distance = transform.getPosition().distance(finalSearchCenter);
                        if (distance <= radius && distance < nearestDistanceHolder[0]) {
                            NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                            nearestTransformHolder[0] = transform;
                            nearestTypeIdHolder[0] = npc != null ? npc.getNPCTypeId() : "unknown";
                            nearestDistanceHolder[0] = distance;
                        }
                    }
                });

                if (nearestTransformHolder[0] == null) {
                    future.complete(McpToolResponse.error("No NPC found within " + radius + " blocks of the search position"));
                    return;
                }

                Vector3d pos = nearestTransformHolder[0].getPosition();
                Rotation3f rotation = nearestTransformHolder[0].getRotation();

                JsonObject position = new JsonObject();
                position.addProperty("x", pos.x());
                position.addProperty("y", pos.y());
                position.addProperty("z", pos.z());
                position.addProperty("yaw", rotation.yaw());
                position.addProperty("pitch", rotation.pitch());

                JsonObject response = new JsonObject();
                response.addProperty("npcTypeId", nearestTypeIdHolder[0]);
                response.addProperty("distanceFromSearchCenter", nearestDistanceHolder[0]);
                response.add("position", position);

                future.complete(McpToolResponse.success(GSON.toJson(response)));
            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[GET_NPC_POSITION] Exception");
                future.complete(McpToolResponse.error("Failed to get NPC position: " + t.getMessage()));
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
            return config.getFeatures().getAdmins().canGetNpcPosition();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canGetNpcPosition();
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
