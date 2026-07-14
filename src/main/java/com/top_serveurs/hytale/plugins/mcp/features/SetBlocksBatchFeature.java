package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SetBlocksBatchFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;
    private final McpConfig config;

    public SetBlocksBatchFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "set_blocks_batch";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "set_blocks_batch",
                "Places up to " + config.getFeatures().getMaxBlocksBatch() + " blocks in one call. IMPORTANT: call get_building_guide first — it contains block names, coordinate rules, support/gravity constraints, and blueprints. ",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        var blockSchema = McpToolSchema.objectProperty(
            java.util.Map.of(
                "x", McpToolSchema.integerProperty("X coordinate"),
                "y", McpToolSchema.integerProperty("Y coordinate"),
                "z", McpToolSchema.integerProperty("Z coordinate"),
                "blockType", McpToolSchema.stringProperty("Block type identifier"),
                "rotation", McpToolSchema.stringProperty("Optional yaw rotation for directional blocks: None, Ninety, OneEighty, or TwoSeventy. Defaults to None.")
            ),
            java.util.List.of("x", "y", "z", "blockType"),
            "Block placement description"
        );

        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "world", McpToolSchema.stringProperty("World UUID"),
                "blocks", McpToolSchema.arrayProperty(blockSchema, "List of blocks to place (max " + config.getFeatures().getMaxBlocksBatch() + ")")
            ),
            java.util.List.of("world", "blocks")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        Object blocksObj = call.getArguments().get("blocks");
        String worldUuidStr = getArgumentAsString(call, "world");

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }

        if (blocksObj == null) {
            return McpToolResponse.error("blocks array is required");
        }

        JsonArray blocks;
        try {
            if (blocksObj instanceof JsonArray) {
                blocks = (JsonArray) blocksObj;
            } else if (blocksObj instanceof List) {
                blocks = GSON.toJsonTree(blocksObj).getAsJsonArray();
            } else {
                JsonElement element = GSON.toJsonTree(blocksObj);
                if (element.isJsonArray()) {
                    blocks = element.getAsJsonArray();
                } else {
                    return McpToolResponse.error("blocks must be an array");
                }
            }
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error parsing blocks array");
            return McpToolResponse.error("Invalid blocks format: " + e.getMessage());
        }

        if (blocks.size() == 0) {
            return McpToolResponse.error("blocks array cannot be empty");
        }

        int maxBlocks = config.getFeatures().getMaxBlocksBatch();
        if (blocks.size() > maxBlocks) {
            return McpToolResponse.error("Maximum " + maxBlocks + " blocks per request");
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

                for (int i = 0; i < blocks.size(); i++) {
                    JsonObject blockData = blocks.get(i).getAsJsonObject();

                    int x = blockData.get("x").getAsInt();
                    int y = blockData.get("y").getAsInt();
                    int z = blockData.get("z").getAsInt();
                    String blockTypeStr = blockData.get("blockType").getAsString();
                    String rotationStr = blockData.has("rotation") && !blockData.get("rotation").isJsonNull()
                            ? blockData.get("rotation").getAsString() : null;

                    BlockType blockType = BlockType.getAssetMap().getAsset(blockTypeStr);
                    if (blockType == null || blockType == BlockType.EMPTY) {
                        failureCount++;
                        JsonObject result = new JsonObject();
                        result.addProperty("x", x);
                        result.addProperty("y", y);
                        result.addProperty("z", z);
                        result.addProperty("status", "error");
                        result.addProperty("message", "Unknown block type: " + blockTypeStr);
                        results.add(result);
                        continue;
                    }

                    Rotation rotation = SetBlockFeature.parseRotation(rotationStr);
                    if (rotation == null) {
                        failureCount++;
                        JsonObject result = new JsonObject();
                        result.addProperty("x", x);
                        result.addProperty("y", y);
                        result.addProperty("z", z);
                        result.addProperty("status", "error");
                        result.addProperty("message", "Invalid rotation: " + rotationStr);
                        results.add(result);
                        continue;
                    }

                    try {
                        // See SetBlockFeature: world.setBlock always hardcodes rotation to None;
                        // placeBlock on the chunk's BlockAccessor is the only way to set facing.
                        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                        if (chunk == null) {
                            failureCount++;
                            JsonObject result = new JsonObject();
                            result.addProperty("x", x);
                            result.addProperty("y", y);
                            result.addProperty("z", z);
                            result.addProperty("status", "error");
                            result.addProperty("message", "Chunk not loaded");
                            results.add(result);
                            continue;
                        }
                        // Overwriting an already-solid block in place can silently fail to persist
                        // (confirmed for both rotation-only and full blockType changes) - break it
                        // to air first whenever the target isn't already air.
                        BlockType existing = chunk.getBlockType(x, y, z);
                        if (existing != null && existing != BlockType.EMPTY) {
                            chunk.breakBlock(x, y, z, 0);
                        }
                        chunk.placeBlock(x, y, z, blockType.getId(), rotation, Rotation.None, Rotation.None, 0);
                        successCount++;

                        JsonObject result = new JsonObject();
                        result.addProperty("x", x);
                        result.addProperty("y", y);
                        result.addProperty("z", z);
                        result.addProperty("blockType", blockTypeStr);
                        result.addProperty("rotation", rotation.name());
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
                response.addProperty("total", blocks.size());
                response.addProperty("success", successCount);
                response.addProperty("failed", failureCount);
                response.add("results", results);

                logger.atInfo().log("[SET_BLOCKS_BATCH] Processed " + blocks.size() + 
                    " blocks (success: " + successCount + ", failed: " + failureCount + ")");

                future.complete(McpToolResponse.success(GSON.toJson(response)));

            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[SET_BLOCKS_BATCH] Exception");
                future.complete(McpToolResponse.error(t.toString()));
            }
        });

        return future.join();
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canSetBlock();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canSetBlock();
        }
        return false;
    }

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }
}
