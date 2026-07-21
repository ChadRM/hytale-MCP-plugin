package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
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
 * Sets one of a Role's named-flag slots directly on the nearest matching NPC (Role.setFlag(int,
 * boolean), the same runtime state ActionSetFlag/SensorFlag read and write from inside Role JSON).
 * Flag *names* only exist at Role-build time - each name gets assigned the next free integer slot
 * on first reference, via a per-Role-asset SlotMapper (confirmed via javap on
 * BuilderSupport/SlotMapper/ActionSetFlag/Role in HytaleServer.jar) - there's no live name->index
 * lookup exposed at runtime, so callers pass the raw slot index and must determine which index
 * corresponds to which flag name empirically (e.g. set index 0, spawn, see which Instruction fires
 * in logs) for a given Role, rather than by name here.
 */
public class SetNpcFlagFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private static final double DEFAULT_RADIUS = 10.0;
    private final HytaleLogger logger;

    public SetNpcFlagFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "set_npc_flag";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "set_npc_flag",
                "Sets a Role flag slot (by integer index, not name - flag names only exist at Role-build "
                        + "time) to true/false on the NPC nearest a search position. Use to force a Role's "
                        + "internal state (e.g. a directional Flag/SetFlag pair in a custom Role) without "
                        + "waiting for the NPC's own AI to reach that state naturally. Index must be "
                        + "determined empirically for a given Role (deploy, set index 0, observe behavior/"
                        + "logs, repeat) - there is no name-to-index lookup at runtime.",
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
                "radius", McpToolSchema.stringProperty("Search radius in blocks. Defaults to " + DEFAULT_RADIUS + "."),
                "npcTypeId", McpToolSchema.stringProperty("If set, only consider NPCs whose role/type id matches this exactly (case-insensitive)."),
                "flagIndex", McpToolSchema.stringProperty("The Role flag slot index to set (integer, 0-based - see tool description)."),
                "value", McpToolSchema.booleanProperty("The value to set the flag to. Defaults to true.")
            ),
            java.util.List.of("world", "flagIndex")
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
        String npcTypeIdFilter = getArgumentAsString(call, "npcTypeId");
        Integer flagIndex = getArgumentAsInteger(call, "flagIndex");
        boolean value = getArgumentAsBooleanOrDefault(call, "value", true);

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }
        if (flagIndex == null) {
            return McpToolResponse.error("flagIndex is required");
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

        int finalFlagIndex = flagIndex;
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

                NPCEntity[] nearestHolder = new NPCEntity[1];
                double[] nearestDistanceHolder = { Double.MAX_VALUE };

                store.forEachChunk(NPCEntity.getComponentType(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> cmdBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (transform == null) continue;
                        double distance = transform.getPosition().distance(finalSearchCenter);
                        if (distance <= radius && distance < nearestDistanceHolder[0]) {
                            NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                            if (npc == null) continue;
                            String npcTypeId = npc.getNPCTypeId();
                            if (npcTypeIdFilter != null && !npcTypeIdFilter.equalsIgnoreCase(npcTypeId)) continue;
                            nearestHolder[0] = npc;
                            nearestDistanceHolder[0] = distance;
                        }
                    }
                });

                if (nearestHolder[0] == null) {
                    String suffix = npcTypeIdFilter != null ? " matching npcTypeId '" + npcTypeIdFilter + "'" : "";
                    future.complete(McpToolResponse.error("No NPC found within " + radius + " blocks of the search position" + suffix));
                    return;
                }

                Role role = nearestHolder[0].getRole();
                if (role == null) {
                    future.complete(McpToolResponse.error("Nearest NPC has no active Role"));
                    return;
                }

                role.setFlag(finalFlagIndex, value);

                JsonObject response = new JsonObject();
                response.addProperty("npcTypeId", nearestHolder[0].getNPCTypeId());
                response.addProperty("flagIndex", finalFlagIndex);
                response.addProperty("value", value);
                response.addProperty("nowSet", role.isFlagSet(finalFlagIndex));

                logger.atInfo().log("[SET_NPC_FLAG] Set flag " + finalFlagIndex + "=" + value + " on nearest "
                        + nearestHolder[0].getNPCTypeId());

                future.complete(McpToolResponse.success(GSON.toJson(response)));
            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[SET_NPC_FLAG] Exception");
                future.complete(McpToolResponse.error("Failed to set NPC flag: " + t.getMessage()));
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

    private Integer getArgumentAsInteger(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        if (value == null) return null;
        try {
            return (int) Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean getArgumentAsBooleanOrDefault(McpToolCall call, String key, boolean defaultValue) {
        Object value = call.getArguments().get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }
}
