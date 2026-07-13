package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bulk read counterpart to set_blocks_batch: scans a bounding box (any corner order accepted,
 * normalized internally) and reports every non-air block found, plus counts of air/unloaded
 * positions. Only non-air blocks are included in the returned list - returning every air block in
 * a large box would blow up payload size for little value, since the caller almost always wants
 * "what's actually built here", not a full lattice.
 *
 * <p>Volume is capped by {@code maxScanVolume} (mirrors set_blocks_batch's maxBlocksBatch cap) to
 * bound both response size and worst-case blocking time: each position not yet resolved triggers
 * getBlockType's normal chunk-load-on-demand behavior, so a very large box spanning many unloaded
 * chunks could stall the world thread for a while. Callers scanning a big area should keep it to
 * chunks they know are already loaded (e.g. right around a player).
 */
public class ScanRegionFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;
    private final McpConfig config;

    public ScanRegionFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "scan_region";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "scan_region",
                "Scans a bounding box (any two opposite corners) and reports every non-air block found, plus counts of air/unloaded positions. Max volume "
                        + config.getFeatures().getMaxScanVolume() + " blocks. Use this instead of repeated get_block calls to check whether a structure is actually built as expected.",
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
                "world", McpToolSchema.stringProperty("World UUID")
            ),
            java.util.List.of("x1", "y1", "z1", "x2", "y2", "z2", "world")
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

        if (x1 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE || z1 == Integer.MIN_VALUE
                || x2 == Integer.MIN_VALUE || y2 == Integer.MIN_VALUE || z2 == Integer.MIN_VALUE) {
            return McpToolResponse.error("x1, y1, z1, x2, y2 and z2 are required integers");
        }

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        int maxVolume = config.getFeatures().getMaxScanVolume();
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

        CompletableFuture<McpToolResponse> future = new CompletableFuture<>();

        world.execute(() -> {
            try {
                JsonArray blocks = new JsonArray();
                int airCount = 0;
                int unloadedCount = 0;

                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            BlockType blockType = world.getBlockType(x, y, z);
                            if (blockType == null) {
                                unloadedCount++;
                            } else if (blockType == BlockType.EMPTY) {
                                airCount++;
                            } else {
                                JsonObject block = new JsonObject();
                                block.addProperty("x", x);
                                block.addProperty("y", y);
                                block.addProperty("z", z);
                                block.addProperty("blockType", blockType.getId());
                                blocks.add(block);
                            }
                        }
                    }
                }

                JsonObject response = new JsonObject();
                response.addProperty("volume", volume);
                response.addProperty("nonAirCount", blocks.size());
                response.addProperty("airCount", airCount);
                response.addProperty("unloadedCount", unloadedCount);
                response.add("blocks", blocks);

                logger.atInfo().log("[SCAN_REGION] Scanned " + volume + " positions ("
                        + blocks.size() + " non-air, " + airCount + " air, " + unloadedCount + " unloaded)");

                future.complete(McpToolResponse.success(GSON.toJson(response)));

            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[SCAN_REGION] Exception");
                future.complete(McpToolResponse.error(t.toString()));
            }
        });

        return future.join();
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canScanRegion();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canScanRegion();
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
