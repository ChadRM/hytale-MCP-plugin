package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side find-and-replace over a bounding box: scans for one block type and, if a
 * replacement type is given, swaps every match in the same pass - all inside a single
 * {@code world.execute()} call, same as {@link ScanRegionFeature}/{@link SetBlocksBatchFeature}.
 *
 * <p>Exists specifically to eliminate the two costs those two features impose when used together
 * for a bulk migration (as this project has repeatedly needed - e.g. swapping every
 * world-gen-ineligible road surface block to a dedicated custom type): {@code scan_region} reports
 * every non-air block in the box (not just matches), and {@code set_blocks_batch} echoes every
 * placed block back - both scale with volume and blow up response size long before the box itself
 * gets particularly large. This feature never returns a raw block list either direction - only a
 * compact summary (counts, a bounding box of matches, and per-axis histograms) - so its own
 * response size scales with the region's *dimensions*, not its *volume*.
 *
 * <p>If {@code replaceBlockType} is omitted, this is a read-only dry run (report matches, write
 * nothing) - useful for characterizing a region (e.g. spotting where a road's footprint jogs via
 * the histograms) before committing to a replacement.
 */
public class ReplaceBlocksInRegionFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;
    private final McpConfig config;

    public ReplaceBlocksInRegionFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "replace_blocks_in_region";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "replace_blocks_in_region",
                "Scans a bounding box (any two opposite corners) for every block matching "
                        + "matchBlockType, and if replaceBlockType is given, replaces each match in "
                        + "the same pass. Returns only a compact summary - matchCount, replacedCount, "
                        + "failedCount, a bounding box of matches, and per-axis (x/z) histograms of "
                        + "match counts - never the raw block list, so response size scales with the "
                        + "region's dimensions, not its volume. Omit replaceBlockType for a read-only "
                        + "dry run. Max volume " + config.getFeatures().getMaxReplaceVolume()
                        + " blocks. Use this instead of scan_region + set_blocks_batch for any bulk "
                        + "find-and-replace over a region.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "x1", McpToolSchema.integerProperty("First corner X coordinate"),
                "y1", McpToolSchema.integerProperty("First corner Y coordinate"),
                "z1", McpToolSchema.integerProperty("First corner Z coordinate"),
                "x2", McpToolSchema.integerProperty("Opposite corner X coordinate"),
                "y2", McpToolSchema.integerProperty("Opposite corner Y coordinate"),
                "z2", McpToolSchema.integerProperty("Opposite corner Z coordinate"),
                "world", McpToolSchema.stringProperty("World UUID"),
                "matchBlockType", McpToolSchema.stringProperty("Block type identifier to search for"),
                "replaceBlockType", McpToolSchema.stringProperty(
                    "Block type identifier to replace matches with. Omit for a read-only dry run "
                        + "(report matches, write nothing).")
            ),
            java.util.List.of("x1", "y1", "z1", "x2", "y2", "z2", "world", "matchBlockType")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        int x1 = getArgumentAsInt(call, "x1");
        int y1 = getArgumentAsInt(call, "y1");
        int z1 = getArgumentAsInt(call, "z1");
        int x2 = getArgumentAsInt(call, "x2");
        int y2 = getArgumentAsInt(call, "y2");
        int z2 = getArgumentAsInt(call, "z2");
        String worldUuidStr = getArgumentAsString(call, "world");
        String matchBlockTypeStr = getArgumentAsString(call, "matchBlockType");
        String replaceBlockTypeStr = getArgumentAsString(call, "replaceBlockType");

        if (x1 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE || z1 == Integer.MIN_VALUE
                || x2 == Integer.MIN_VALUE || y2 == Integer.MIN_VALUE || z2 == Integer.MIN_VALUE) {
            return McpToolResponse.error("x1, y1, z1, x2, y2 and z2 are required integers");
        }

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }

        if (matchBlockTypeStr == null) {
            return McpToolResponse.error("matchBlockType is required");
        }

        BlockType matchBlockType = BlockType.getAssetMap().getAsset(matchBlockTypeStr);
        if (matchBlockType == null) {
            return McpToolResponse.error("Unknown block type: " + matchBlockTypeStr);
        }

        BlockType replaceBlockType = null;
        if (replaceBlockTypeStr != null) {
            replaceBlockType = BlockType.getAssetMap().getAsset(replaceBlockTypeStr);
            if (replaceBlockType == null || replaceBlockType == BlockType.EMPTY) {
                return McpToolResponse.error("Unknown block type: " + replaceBlockTypeStr);
            }
        }

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        int maxVolume = config.getFeatures().getMaxReplaceVolume();
        if (volume > maxVolume) {
            return McpToolResponse.error("Region volume " + volume + " exceeds maximum " + maxVolume + " blocks - shrink the box");
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

        BlockType finalReplaceBlockType = replaceBlockType;
        CompletableFuture<McpToolResponse> future = new CompletableFuture<>();

        world.execute(() -> {
            try {
                int matchCount = 0;
                int replacedCount = 0;
                int failedCount = 0;
                int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
                int yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
                int zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
                TreeMap<Integer, Integer> histogramByX = new TreeMap<>();
                TreeMap<Integer, Integer> histogramByZ = new TreeMap<>();

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                        if (chunk == null) {
                            continue;
                        }
                        for (int y = minY; y <= maxY; y++) {
                            BlockType blockType = world.getBlockType(x, y, z);
                            if (blockType != matchBlockType) {
                                continue;
                            }

                            matchCount++;
                            xMin = Math.min(xMin, x);
                            xMax = Math.max(xMax, x);
                            yMin = Math.min(yMin, y);
                            yMax = Math.max(yMax, y);
                            zMin = Math.min(zMin, z);
                            zMax = Math.max(zMax, z);
                            histogramByX.merge(x, 1, Integer::sum);
                            histogramByZ.merge(z, 1, Integer::sum);

                            if (finalReplaceBlockType != null) {
                                // Same 7-arg placeBlock overload SetBlocksBatchFeature uses -
                                // test=false skips the occupancy check that would otherwise reject
                                // overwriting an already-solid block.
                                RotationTuple rotationTuple = RotationTuple.of(Rotation.None, Rotation.None, Rotation.None);
                                boolean placed = chunk.placeBlock(x, y, z, finalReplaceBlockType.getId(), rotationTuple, 0, false);
                                if (placed) {
                                    replacedCount++;
                                } else {
                                    failedCount++;
                                }
                            }
                        }
                    }
                }

                JsonObject response = new JsonObject();
                response.addProperty("volume", volume);
                response.addProperty("matchCount", matchCount);
                response.addProperty("replacedCount", replacedCount);
                response.addProperty("failedCount", failedCount);

                if (matchCount > 0) {
                    JsonObject boundingBox = new JsonObject();
                    boundingBox.addProperty("xMin", xMin);
                    boundingBox.addProperty("xMax", xMax);
                    boundingBox.addProperty("yMin", yMin);
                    boundingBox.addProperty("yMax", yMax);
                    boundingBox.addProperty("zMin", zMin);
                    boundingBox.addProperty("zMax", zMax);
                    response.add("boundingBox", boundingBox);
                } else {
                    response.add("boundingBox", null);
                }

                JsonObject histX = new JsonObject();
                for (var entry : histogramByX.entrySet()) {
                    histX.addProperty(String.valueOf(entry.getKey()), entry.getValue());
                }
                response.add("histogramByX", histX);

                JsonObject histZ = new JsonObject();
                for (var entry : histogramByZ.entrySet()) {
                    histZ.addProperty(String.valueOf(entry.getKey()), entry.getValue());
                }
                response.add("histogramByZ", histZ);

                logger.atInfo().log("[REPLACE_BLOCKS_IN_REGION] Scanned " + volume + " positions for "
                        + matchBlockTypeStr + " (matched " + matchCount + ", replaced " + replacedCount
                        + ", failed " + failedCount + ")");

                future.complete(McpToolResponse.success(GSON.toJson(response)));

            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[REPLACE_BLOCKS_IN_REGION] Exception");
                future.complete(McpToolResponse.error(t.toString()));
            }
        });

        return future.join();
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canReplaceBlocksInRegion();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canReplaceBlocksInRegion();
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

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }
}
