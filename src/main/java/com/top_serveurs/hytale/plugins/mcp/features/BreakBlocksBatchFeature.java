package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bulk counterpart to break_block, mirroring set_blocks_batch's shape - clears up to
 * {@code maxBlocksBatch} coordinates back to air in one call instead of one round-trip per block.
 */
public class BreakBlocksBatchFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;
    private final McpConfig config;

    public BreakBlocksBatchFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "break_blocks_batch";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "break_blocks_batch",
                "Clears up to " + config.getFeatures().getMaxBlocksBatch() + " blocks back to air in one call - the batch counterpart to break_block.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        var coordSchema = McpToolSchema.objectProperty(
            java.util.Map.of(
                "x", McpToolSchema.integerProperty("X coordinate"),
                "y", McpToolSchema.integerProperty("Y coordinate"),
                "z", McpToolSchema.integerProperty("Z coordinate")
            ),
            java.util.List.of("x", "y", "z"),
            "Coordinate to clear"
        );

        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "world", McpToolSchema.stringProperty("World UUID"),
                "coords", McpToolSchema.arrayProperty(coordSchema, "List of coordinates to clear (max " + config.getFeatures().getMaxBlocksBatch() + ")")
            ),
            java.util.List.of("world", "coords")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        Object coordsObj = call.getArguments().get("coords");
        String worldUuidStr = getArgumentAsString(call, "world");

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }

        if (coordsObj == null) {
            return McpToolResponse.error("coords array is required");
        }

        JsonArray coords;
        try {
            if (coordsObj instanceof JsonArray) {
                coords = (JsonArray) coordsObj;
            } else if (coordsObj instanceof List) {
                coords = GSON.toJsonTree(coordsObj).getAsJsonArray();
            } else {
                JsonElement element = GSON.toJsonTree(coordsObj);
                if (element.isJsonArray()) {
                    coords = element.getAsJsonArray();
                } else {
                    return McpToolResponse.error("coords must be an array");
                }
            }
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error parsing coords array");
            return McpToolResponse.error("Invalid coords format: " + e.getMessage());
        }

        if (coords.size() == 0) {
            return McpToolResponse.error("coords array cannot be empty");
        }

        int maxBlocks = config.getFeatures().getMaxBlocksBatch();
        if (coords.size() > maxBlocks) {
            return McpToolResponse.error("Maximum " + maxBlocks + " coordinates per request");
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
                JsonArray results = new JsonArray();
                int successCount = 0;
                int failureCount = 0;

                for (int i = 0; i < coords.size(); i++) {
                    JsonObject coord = coords.get(i).getAsJsonObject();

                    int x = coord.get("x").getAsInt();
                    int y = coord.get("y").getAsInt();
                    int z = coord.get("z").getAsInt();

                    try {
                        boolean changed = world.breakBlock(x, y, z, 0);
                        successCount++;

                        JsonObject result = new JsonObject();
                        result.addProperty("x", x);
                        result.addProperty("y", y);
                        result.addProperty("z", z);
                        result.addProperty("changed", changed);
                        result.addProperty("status", "success");
                        results.add(result);
                    } catch (Exception e) {
                        failureCount++;
                        JsonObject result = new JsonObject();
                        result.addProperty("x", x);
                        result.addProperty("y", y);
                        result.addProperty("z", z);
                        result.addProperty("status", "error");
                        result.addProperty("message", e.getMessage());
                        results.add(result);
                    }
                }

                JsonObject response = new JsonObject();
                response.addProperty("total", coords.size());
                response.addProperty("success", successCount);
                response.addProperty("failed", failureCount);
                response.add("results", results);

                logger.atInfo().log("[BREAK_BLOCKS_BATCH] Processed " + coords.size()
                        + " coordinates (success: " + successCount + ", failed: " + failureCount + ")");

                future.complete(McpToolResponse.success(GSON.toJson(response)));

            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[BREAK_BLOCKS_BATCH] Exception");
                future.complete(McpToolResponse.error(t.toString()));
            }
        });

        return future.join();
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canBreakBlock();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canBreakBlock();
        }
        return false;
    }

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }
}
