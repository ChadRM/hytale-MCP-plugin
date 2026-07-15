package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
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
 * Removes NPC entities near a position (either explicit x/y/z or near a named player). Finds
 * candidates by scanning every entity carrying an NPCEntity component and comparing live Transform
 * position against the search center - there's no per-NPC handle returned by spawn_npc to target
 * directly (yet), so "nearest within radius" is the practical way to despawn something just placed.
 * Removes only the single nearest match by default; set all:true to clear everything in radius.
 */
public class DespawnNpcFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private static final double DEFAULT_RADIUS = 10.0;
    private final HytaleLogger logger;

    public DespawnNpcFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "despawn_npc";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "despawn_npc",
                "Removes NPC entities near a position. Either give an explicit x/y/z, or give player "
                        + "to search near that player. Removes only the single nearest NPC within radius "
                        + "(default " + DEFAULT_RADIUS + ") by default; set all:true to remove every NPC in radius.",
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
                "all", McpToolSchema.booleanProperty("If true, remove every NPC within radius instead of just the nearest one. Defaults to false.")
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
        boolean all = getArgumentAsBoolean(call, "all");

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
                Vector3d center;

                if (hasExplicitPosition) {
                    center = new Vector3d(explicitX, explicitY, explicitZ);
                } else {
                    PlayerRef player = findPlayer(playerIdentifier);
                    if (player == null) {
                        future.complete(McpToolResponse.error("Player not found: " + playerIdentifier));
                        return;
                    }
                    center = player.getTransform().getPosition();
                }

                Store<EntityStore> store = world.getEntityStore().getStore();
                Vector3d searchCenter = center;

                List<Candidate> candidates = new ArrayList<>();
                store.forEachChunk(NPCEntity.getComponentType(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> cmdBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (transform == null) continue;
                        Vector3d pos = transform.getPosition();
                        double distance = pos.distance(searchCenter);
                        if (distance <= radius) {
                            NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                            String npcTypeId = npc != null ? npc.getNPCTypeId() : "unknown";
                            candidates.add(new Candidate(chunk.getReferenceTo(i), npcTypeId, pos, distance));
                        }
                    }
                });

                candidates.sort((a, b) -> Double.compare(a.distance, b.distance));

                List<Candidate> toRemove = all
                        ? candidates
                        : (candidates.isEmpty() ? candidates : candidates.subList(0, 1));

                JsonArray removedArray = new JsonArray();
                for (Candidate c : toRemove) {
                    store.removeEntity(c.ref, RemoveReason.REMOVE);

                    JsonObject entry = new JsonObject();
                    entry.addProperty("npcTypeId", c.npcTypeId);
                    entry.addProperty("distance", c.distance);
                    JsonObject posJson = new JsonObject();
                    posJson.addProperty("x", c.position.x());
                    posJson.addProperty("y", c.position.y());
                    posJson.addProperty("z", c.position.z());
                    entry.add("position", posJson);
                    removedArray.add(entry);
                }

                JsonObject response = new JsonObject();
                response.addProperty("candidatesFound", candidates.size());
                response.addProperty("removedCount", toRemove.size());
                response.add("removed", removedArray);

                logger.atInfo().log("[DESPAWN_NPC] Removed " + toRemove.size() + " of " + candidates.size()
                        + " candidates within " + radius + " blocks");

                future.complete(McpToolResponse.success(GSON.toJson(response)));
            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[DESPAWN_NPC] Exception");
                future.complete(McpToolResponse.error("Failed to despawn NPC: " + t.getMessage()));
            }
        });

        return future.join();
    }

    private static final class Candidate {
        final Ref<EntityStore> ref;
        final String npcTypeId;
        final Vector3d position;
        final double distance;

        Candidate(Ref<EntityStore> ref, String npcTypeId, Vector3d position, double distance) {
            this.ref = ref;
            this.npcTypeId = npcTypeId;
            this.position = position;
            this.distance = distance;
        }
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

    private boolean getArgumentAsBoolean(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }
}
