package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
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
 * directly; it's reached via {@code getChunk(ChunkUtil.indexChunkFromBlock(x, z))} then
 * {@code chunk.getHeight(x & ChunkUtil.SIZE_MASK, z & ChunkUtil.SIZE_MASK)}.
 *
 * <p>Deliberately calls {@code World.getChunk(long)}, not {@code getChunkIfLoaded(long)} - the
 * latter returns {@code null} for an unloaded chunk instead of loading it, which made this tool
 * silently fail (report every column as unloaded) whenever no player was nearby to keep chunks
 * loaded, even though {@code get_block}/{@code scan_region} worked fine in the same situation.
 * Confirmed via decompiling {@code IChunkAccessorSync.getBlock} (which {@code getBlockType} calls
 * internally) that it forces a load through this exact same {@code getChunk(long)} default method -
 * {@code getChunkIfLoaded} was just the wrong call for a tool that's supposed to work regardless of
 * player proximity. {@code getChunk} blocks synchronously (safe from the world thread specifically -
 * it pumps the world's own task queue while waiting rather than deadlocking) until the chunk loads,
 * so worst-case latency scales with how many distinct, currently-unloaded chunks a call touches;
 * {@code maxHeightmapSamples} already bounds that.
 *
 * <p>The engine's own heightmap reports the literal topmost block, which is overhanging tree canopy
 * ({@code Plant_Leaves_*}) more often than not near vegetation - by default this walks down past
 * canopy block types to report the block a builder would actually stand on, keeping the raw canopy
 * top too so callers can tell it happened. {@link #FOLIAGE_ID_SUBSTRING} is a hardcoded guess at
 * every leaf-type id in the current pack; re-derive it (grep the live asset registry / {@code
 * list_blocks} for {@code Leaves}) against {@code get_server_info}'s {@code hytaleVersion} whenever
 * that version changes, since new tree species could ship under a different naming scheme.
 *
 * <p>Also reports the water surface (if any) above each column's ground: fluids live on a separate
 * per-chunk data channel from block type in this engine ({@code WorldChunk.getFluidId}/{@code
 * getFluidLevel}), so a lake bed can be all `chunk.getHeight` ever reports, with the actual water
 * surface floating invisibly above it as far as block-type-only tools are concerned. Confirmed live:
 * a player standing in visibly 2-block-deep water had every block-type-based tool report plain air.
 */
public class GetHeightmapFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private static final String FOLIAGE_ID_SUBSTRING = "_Leaves_";
    private static final int MAX_FOLIAGE_SKIP_DEPTH = 24;
    private static final int MAX_FLUID_SCAN_HEIGHT = 16;
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
                "Gets the ground surface height and top block type for every column in an X/Z area, plus the water surface height/type above it if any (fluids are tracked separately from block type, so they'd otherwise be invisible) - a compact terrain-shape query for roads/rivers/siting, much lighter than scan_region for the same area. Max "
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
                "world", McpToolSchema.stringProperty("World UUID"),
                "skipFoliage", McpToolSchema.booleanProperty("Walk down past overhanging tree canopy to report the actual ground surface instead of the literal topmost block (optional, default true)")
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
        Boolean skipFoliageArg = getArgumentAsBoolean(call, "skipFoliage");
        boolean skipFoliage = skipFoliageArg == null || skipFoliageArg;

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

                        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                        if (chunk == null) {
                            column.addProperty("loaded", false);
                            unloadedCount++;
                            columns.add(column);
                            continue;
                        }

                        int localX = x & ChunkUtil.SIZE_MASK;
                        int localZ = z & ChunkUtil.SIZE_MASK;
                        short canopyTopY = chunk.getHeight(localX, localZ);

                        column.addProperty("loaded", true);

                        int groundY = canopyTopY;
                        BlockType groundType = world.getBlockType(x, groundY, z);
                        int skipped = 0;
                        if (skipFoliage) {
                            while (isFoliageCanopy(groundType) && skipped < MAX_FOLIAGE_SKIP_DEPTH) {
                                groundY--;
                                groundType = world.getBlockType(x, groundY, z);
                                skipped++;
                            }
                        }

                        column.addProperty("surfaceY", groundY);
                        if (groundType != null && groundType != BlockType.EMPTY) {
                            column.addProperty("blockType", groundType.getId());
                        } else {
                            column.add("blockType", null);
                        }

                        if (skipped > 0) {
                            column.addProperty("canopyTopY", (int) canopyTopY);
                            BlockType canopyType = world.getBlockType(x, canopyTopY, z);
                            column.addProperty("canopyBlockType", canopyType != null ? canopyType.getId() : null);
                        }

                        Integer waterSurfaceY = null;
                        String fluidType = null;
                        for (int fy = groundY + 1; fy <= groundY + MAX_FLUID_SCAN_HEIGHT; fy++) {
                            int fluidId = chunk.getFluidId(x, fy, z);
                            if (fluidId != Fluid.EMPTY_ID) {
                                waterSurfaceY = fy;
                                Fluid fluid = Fluid.getAssetMap().getAsset(fluidId);
                                fluidType = fluid != null ? fluid.getId() : null;
                            }
                        }
                        if (waterSurfaceY != null) {
                            column.addProperty("waterSurfaceY", waterSurfaceY);
                            column.addProperty("fluidType", fluidType);
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

    private boolean isFoliageCanopy(BlockType type) {
        if (type == null || type == BlockType.EMPTY) {
            return false;
        }
        String id = type.getId();
        return id != null && id.contains(FOLIAGE_ID_SUBSTRING);
    }

    private Boolean getArgumentAsBoolean(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
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
