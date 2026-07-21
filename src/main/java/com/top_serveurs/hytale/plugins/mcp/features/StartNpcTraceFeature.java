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
import com.hypixel.hytale.server.npc.role.Role;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;
import org.joml.Vector3d;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Starts a fine-grained position+heading-flag trace of the NPC nearest a search position, writing
 * CSV rows to a dedicated file on a fixed wall-clock interval until stop_npc_trace is called. Built
 * because polling get_npc_position by hand - even every few seconds - misses the exact moment a
 * Role's committed heading flips. This samples much faster (default 200ms) and reads the Role's raw
 * flag bits directly (Role.isFlagSet, the same accessor set_npc_flag already uses), so the trace
 * shows precisely when and where a heading commitment changes, not just where the NPC ends up
 * seconds later.
 *
 * Deliberately off by default and only writes while a trace is actively running - each start begins
 * a brand new file rather than appending forever, and stop_npc_trace closes it, so a debug session
 * never silently eats disk space once it's done. Only one trace can run at a time.
 */
public class StartNpcTraceFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private static final double DEFAULT_RADIUS = 10.0;
    private static final long DEFAULT_INTERVAL_MS = 200;
    private final HytaleLogger logger;

    public StartNpcTraceFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "start_npc_trace";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "start_npc_trace",
                "Starts a fine-grained trace of the NPC nearest a search position (either an explicit "
                        + "x/y/z or near a named player), sampling its live position and Role flag bits on "
                        + "a fixed interval (default " + DEFAULT_INTERVAL_MS + "ms) and writing CSV rows to "
                        + "a dedicated file until stop_npc_trace is called. Off by default - only one trace "
                        + "can run at a time, and it writes nothing until started and nothing after "
                        + "stopped, so it never silently eats disk space.",
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
                "radius", McpToolSchema.stringProperty("Search radius in blocks, re-used every sample to re-find the NPC. Defaults to " + DEFAULT_RADIUS + "."),
                "npcTypeId", McpToolSchema.stringProperty("If set, only trace an NPC whose role/type id matches this exactly (case-insensitive)."),
                "intervalMs", McpToolSchema.integerProperty("Sampling interval in milliseconds. Defaults to " + DEFAULT_INTERVAL_MS + ".")
            ),
            java.util.List.of("world")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        if (NpcTraceState.isActive()) {
            return McpToolResponse.error("A trace is already running (" + NpcTraceState.filePath
                    + ") - call stop_npc_trace first");
        }

        String worldUuidStr = getArgumentAsString(call, "world");
        String playerIdentifier = getArgumentAsString(call, "player");
        Double explicitX = getArgumentAsDouble(call, "x");
        Double explicitY = getArgumentAsDouble(call, "y");
        Double explicitZ = getArgumentAsDouble(call, "z");
        double radius = getArgumentAsDoubleOrDefault(call, "radius", DEFAULT_RADIUS);
        String npcTypeIdFilter = getArgumentAsString(call, "npcTypeId");
        long intervalMs = (long) getArgumentAsDoubleOrDefault(call, "intervalMs", DEFAULT_INTERVAL_MS);

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

        long finalIntervalMs = intervalMs;
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

                NearestResult nearest = findNearest(world, searchCenter, radius, npcTypeIdFilter);
                if (nearest == null) {
                    future.complete(McpToolResponse.error("No NPC found within " + radius + " blocks of the search position"));
                    return;
                }

                File dir = new File("mods/MCP/traces");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                File file = new File(dir, timestamp + "-" + nearest.npcTypeId + ".csv");

                BufferedWriter writer = Files.newBufferedWriter(file.toPath());
                writer.write("elapsedMs,x,y,z,yaw,flag0,flag1,flag2,flag3");
                writer.newLine();
                writer.flush();

                NpcTraceState.writer = writer;
                NpcTraceState.world = world;
                NpcTraceState.lastKnownPosition = nearest.position;
                NpcTraceState.npcTypeIdFilter = npcTypeIdFilter;
                NpcTraceState.radius = radius;
                NpcTraceState.startTimeMillis = System.currentTimeMillis();
                NpcTraceState.filePath = file.getPath();
                NpcTraceState.sampleCount.set(0);
                NpcTraceState.missCount.set(0);

                NpcTraceState.executor = Executors.newSingleThreadScheduledExecutor();
                NpcTraceState.task = NpcTraceState.executor.scheduleAtFixedRate(
                        StartNpcTraceFeature::sampleTick, finalIntervalMs, finalIntervalMs, TimeUnit.MILLISECONDS);

                JsonObject response = new JsonObject();
                response.addProperty("tracing", true);
                response.addProperty("npcTypeId", nearest.npcTypeId);
                response.addProperty("file", file.getPath());
                response.addProperty("intervalMs", finalIntervalMs);

                logger.atInfo().log("[START_NPC_TRACE] Tracing " + nearest.npcTypeId + " to " + file.getPath()
                        + " every " + finalIntervalMs + "ms");

                future.complete(McpToolResponse.success(GSON.toJson(response)));
            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[START_NPC_TRACE] Exception");
                future.complete(McpToolResponse.error("Failed to start NPC trace: " + t.getMessage()));
            }
        });

        return future.join();
    }

    private static void sampleTick() {
        World world = NpcTraceState.world;
        if (world == null) return;
        world.execute(() -> {
            BufferedWriter writer = NpcTraceState.writer;
            if (writer == null) return; // stopped between schedule and execution

            try {
                NearestResult nearest = findNearest(world, NpcTraceState.lastKnownPosition,
                        NpcTraceState.radius, NpcTraceState.npcTypeIdFilter);
                if (nearest == null) {
                    NpcTraceState.missCount.incrementAndGet();
                    return;
                }

                NpcTraceState.lastKnownPosition = nearest.position;
                long elapsed = System.currentTimeMillis() - NpcTraceState.startTimeMillis;

                boolean[] flags = new boolean[4];
                Role role = nearest.role;
                for (int i = 0; i < 4; i++) {
                    flags[i] = role != null && role.isFlagSet(i);
                }

                writer.write(elapsed + "," + nearest.position.x() + "," + nearest.position.y() + ","
                        + nearest.position.z() + "," + nearest.yaw + ","
                        + flags[0] + "," + flags[1] + "," + flags[2] + "," + flags[3]);
                writer.newLine();
                NpcTraceState.sampleCount.incrementAndGet();
            } catch (IOException e) {
                // best-effort - a single failed sample shouldn't kill the whole trace
            }
        });
    }

    static NearestResult findNearest(World world, Vector3d searchCenter, double radius, String npcTypeIdFilter) {
        Store<EntityStore> store = world.getEntityStore().getStore();

        NearestResult[] holder = new NearestResult[1];
        double[] nearestDistanceHolder = { Double.MAX_VALUE };

        store.forEachChunk(NPCEntity.getComponentType(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> cmdBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) continue;
                double distance = transform.getPosition().distance(searchCenter);
                if (distance <= radius && distance < nearestDistanceHolder[0]) {
                    NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                    String npcTypeId = npc != null ? npc.getNPCTypeId() : "unknown";
                    if (npcTypeIdFilter != null && !npcTypeIdFilter.equalsIgnoreCase(npcTypeId)) continue;
                    Rotation3f rotation = transform.getRotation();
                    Role role = npc != null ? npc.getRole() : null;
                    holder[0] = new NearestResult(npcTypeId, transform.getPosition(), rotation.yaw(), role);
                    nearestDistanceHolder[0] = distance;
                }
            }
        });

        return holder[0];
    }

    static final class NearestResult {
        final String npcTypeId;
        final Vector3d position;
        final float yaw;
        final Role role;

        NearestResult(String npcTypeId, Vector3d position, float yaw, Role role) {
            this.npcTypeId = npcTypeId;
            this.position = position;
            this.yaw = yaw;
            this.role = role;
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
            return config.getFeatures().getAdmins().canNpcTrace();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canNpcTrace();
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
