package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reports ground surface height (+ top block type) per column over an X/Z area - a compact 2D
 * alternative to scan_region for terrain-following builds (roads, rivers, settlement siting) where
 * a full 3D block dump would be mostly air and wildly oversized for the question being asked.
 *
 * <p>Uses {@code WorldChunk.getHeight(localX, localZ)} - the engine's own maintained heightmap - for
 * an O(1) lookup per column instead of scanning blocks top-down. {@code World} doesn't expose this
 * directly; it's reached via {@code getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z))} then
 * {@code chunk.getHeight(x & ChunkUtil.SIZE_MASK, z & ChunkUtil.SIZE_MASK)}.
 */
public class GetHeightmapFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;
    private final McpConfig config;

    public GetHeightmapFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "get_heightmap";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "get_heightmap",
                "Gets the ground surface height and top block type for every column in an X/Z area - a compact terrain-shape query for roads/rivers/siting, much lighter than scan_region for the same area. Max "
                        + config.getFeatures().getMaxHeightmapSamples() + " sampled columns; use the stride parameter for a coarse scan over a large area.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "x1", McpToolSchema.integerProperty("First corner X coordinate"),
                "z1", McpToolSchema.integerProperty("First corner Z coordinate"),
                "x2", McpToolSchema.integerProperty("Opposite corner X coordinate"),
                "z2", McpToolSchema.integerProperty("Opposite corner Z coordinate"),
                "stride", McpToolSchema.integerProperty("Sample every Nth block per axis (optional, default 1 - use a larger stride for a coarse scan over a large area)"),
                "world", McpToolSchema.stringProperty("World UUID")
            ),
            java.util.List.of("x1", "z1", "x2", "z2", "world")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {

        int x1 = getArgumentAsInt(call, "x1");
        int z1 = getArgumentAsInt(call, "z1");
        int x2 = getArgumentAsInt(call, "x2");
        int z2 = getArgumentAsInt(call, "z2");
        Integer strideArg = getArgumentAsInteger(call, "stride");
        int stride = (strideArg == null || strideArg < 1) ? 1 : strideArg;
        String worldUuidStr = getArgumentAsString(call, "world");

        if (x1 == Integer.MIN_VALUE || z1 == Integer.MIN_VALUE || x2 == Integer.MIN_VALUE || z2 == Integer.MIN_VALUE) {
            return McpToolResponse.error("x1, z1, x2 and z2 are required integers");
        }

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        long samplesX = (long) (maxX - minX) / stride + 1;
        long samplesZ = (long) (maxZ - minZ) / stride + 1;
        long totalSamples = samplesX * samplesZ;
        int maxSamples = config.getFeatures().getMaxHeightmapSamples();
        if (totalSamples > maxSamples) {
            return McpToolResponse.error("Sample count " + totalSamples + " exceeds maximum " + maxSamples + " - shrink the area or increase stride");
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
                JsonArray columns = new JsonArray();
                int unloadedCount = 0;

                for (int x = minX; x <= maxX; x += stride) {
                    for (int z = minZ; z <= maxZ; z += stride) {
                        JsonObject column = new JsonObject();
                        column.addProperty("x", x);
                        column.addProperty("z", z);

                        WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
                        if (chunk == null) {
                            column.addProperty("loaded", false);
                            unloadedCount++;
                            columns.add(column);
                            continue;
                        }

                        int localX = x & ChunkUtil.SIZE_MASK;
                        int localZ = z & ChunkUtil.SIZE_MASK;
                        short surfaceY = chunk.getHeight(localX, localZ);

                        column.addProperty("loaded", true);
                        column.addProperty("surfaceY", surfaceY);

                        BlockType surfaceType = world.getBlockType(x, surfaceY, z);
                        if (surfaceType != null && surfaceType != BlockType.EMPTY) {
                            column.addProperty("blockType", surfaceType.getId());
                        } else {
                            column.add("blockType", null);
                        }

                        columns.add(column);
                    }
                }

                JsonObject response = new JsonObject();
                response.addProperty("totalSamples", totalSamples);
                response.addProperty("unloadedCount", unloadedCount);
                response.add("columns", columns);

                logger.atInfo().log("[GET_HEIGHTMAP] Sampled " + totalSamples + " columns (" + unloadedCount + " unloaded)");

                future.complete(McpToolResponse.success(GSON.toJson(response)));

            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[GET_HEIGHTMAP] Exception");
                future.complete(McpToolResponse.error(t.toString()));
            }
        });

        return future.join();
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canGetHeightmap();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canGetHeightmap();
        }
        return false;
    }

    private int getArgumentAsInt(McpToolCall call, String key) {
        try {
            Object value = call.getArguments().get(key);
            if (value == null) return Integer.MIN_VALUE;
            if (value instanceof Number) return ((Number) value).intValue();
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    private Integer getArgumentAsInteger(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }
}
