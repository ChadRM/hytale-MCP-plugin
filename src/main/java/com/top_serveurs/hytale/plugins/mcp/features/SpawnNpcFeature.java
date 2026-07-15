package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;
import it.unimi.dsi.fastutil.Pair;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Spawns a real NPC entity by role name, either at an explicit position or near a named player.
 * Backed by NPCPlugin.spawnNPC(Store, role, flock, position, rotation) - the same call the real
 * "/npc spawn" player command uses internally (that command is AbstractPlayerCommand and can't be
 * driven from the console sender execute_command already uses, so this calls the underlying API
 * directly instead of shelling out). Use list_npc_roles first to find a valid role name.
 */
public class SpawnNpcFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private static final double DEFAULT_OFFSET_FORWARD = 3.0;
    private final HytaleLogger logger;
    private final McpConfig config;

    public SpawnNpcFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "spawn_npc";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "spawn_npc",
                "Spawns an NPC by role name (see list_npc_roles for valid values). Either give an "
                        + "explicit x/y/z, or give player to spawn a few blocks in front of that player "
                        + "(control the distance with offsetForward, default " + DEFAULT_OFFSET_FORWARD + ").",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "world", McpToolSchema.stringProperty("World UUID"),
                "role", McpToolSchema.stringProperty("NPC role name to spawn (see list_npc_roles)"),
                "player", McpToolSchema.stringProperty("Player name or UUID to spawn near. Required if x/y/z are omitted."),
                "offsetForward", McpToolSchema.stringProperty(
                        "Distance in blocks to spawn in front of the player, along their facing yaw. Only used with player. Defaults to " + DEFAULT_OFFSET_FORWARD + "."),
                "x", McpToolSchema.stringProperty("Explicit X coordinate. Required together with y/z if player is omitted."),
                "y", McpToolSchema.stringProperty("Explicit Y coordinate."),
                "z", McpToolSchema.stringProperty("Explicit Z coordinate.")
            ),
            java.util.List.of("world", "role")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        String worldUuidStr = getArgumentAsString(call, "world");
        String role = getArgumentAsString(call, "role");
        String playerIdentifier = getArgumentAsString(call, "player");
        Double explicitX = getArgumentAsDouble(call, "x");
        Double explicitY = getArgumentAsDouble(call, "y");
        Double explicitZ = getArgumentAsDouble(call, "z");
        double offsetForward = getArgumentAsDoubleOrDefault(call, "offsetForward", DEFAULT_OFFSET_FORWARD);

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }
        if (role == null || role.isEmpty()) {
            return McpToolResponse.error("role is required");
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
                Vector3d position;

                if (hasExplicitPosition) {
                    position = new Vector3d(explicitX, explicitY, explicitZ);
                } else {
                    PlayerRef player = findPlayer(playerIdentifier);
                    if (player == null) {
                        future.complete(McpToolResponse.error("Player not found: " + playerIdentifier));
                        return;
                    }

                    com.hypixel.hytale.math.vector.Transform transform = player.getTransform();
                    Vector3d playerPos = transform.getPosition();
                    float yawDegrees = transform.getRotation().yaw();
                    double yawRadians = Math.toRadians(yawDegrees);

                    // Yaw 0 = East (+X) on this engine, confirmed live in the Clacks Tower mod's
                    // Facing.java: forwardX = cos(yaw), forwardZ = sin(yaw) matches that convention.
                    double forwardX = Math.cos(yawRadians);
                    double forwardZ = Math.sin(yawRadians);

                    position = new Vector3d(
                            playerPos.x() + forwardX * offsetForward,
                            playerPos.y(),
                            playerPos.z() + forwardZ * offsetForward
                    );
                }

                Store<EntityStore> store = world.getEntityStore().getStore();

                Pair<Ref<EntityStore>, INonPlayerCharacter> result =
                        NPCPlugin.get().spawnNPC(store, role, null, position, Rotation3f.ZERO);

                if (result == null) {
                    future.complete(McpToolResponse.error(
                            "Failed to spawn NPC with role '" + role + "' - role not found, not spawnable, "
                                    + "or spawn position invalid. Check list_npc_roles for valid role names."));
                    return;
                }

                JsonObject posJson = new JsonObject();
                posJson.addProperty("x", position.x());
                posJson.addProperty("y", position.y());
                posJson.addProperty("z", position.z());

                JsonObject response = new JsonObject();
                response.addProperty("role", role);
                response.addProperty("npcTypeId", result.second().getNPCTypeId());
                response.add("position", posJson);

                logger.atInfo().log("[SPAWN_NPC] Spawned role '" + role + "' at " + position);

                future.complete(McpToolResponse.success(GSON.toJson(response)));
            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[SPAWN_NPC] Exception");
                future.complete(McpToolResponse.error("Failed to spawn NPC: " + t.getMessage()));
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
