package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Verifies a planned block placement against live world state in one call, replacing the
 * scan_region-then-diff-client-side pattern that was previously hand-rolled for every build/repair
 * pass (see hytale-block-mod's project_road_building_rules memory - this tool exists specifically
 * because that manual diff script was rewritten a dozen+ times in one session and, separately, a
 * "floating block" bug slipped through because the support check wasn't run consistently).
 *
 * <p>For each entry in the input list, compares the live block at (x,y,z) against the expected
 * blockType. Optionally also checks that (x,y-1,z) is non-air, catching blocks placed with nothing
 * underneath (the concrete bug that motivated adding this check as a first-class option rather than
 * something the caller has to remember to do separately).
 */
public class VerifyPlacementFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;
    private final McpConfig config;

    public VerifyPlacementFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "verify_placement";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "verify_placement",
                "Verifies a list of expected block placements against live world state in one call - replaces "
                        + "manually scanning a region and diffing it client-side. For each {x,y,z,blockType} entry, "
                        + "reports whether the live block matches. Set checkSupport:true to also flag any entry whose "
                        + "block directly below (y-1) is air/unloaded (a floating block with nothing holding it up). "
                        + "Always use this after any set_blocks_batch/break_blocks_batch call before considering a "
                        + "build step done - batch placement APIs can silently report success for cells that didn't "
                        + "actually change. Max " + config.getFeatures().getMaxBlocksBatch() + " entries per call.",
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
                "blockType", McpToolSchema.stringProperty("Expected block type identifier at this position")
            ),
            java.util.List.of("x", "y", "z", "blockType"),
            "Expected block placement to verify"
        );

        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "world", McpToolSchema.stringProperty("World UUID"),
                "blocks", McpToolSchema.arrayProperty(blockSchema, "List of expected placements to verify (max " + config.getFeatures().getMaxBlocksBatch() + ")"),
                "checkSupport", McpToolSchema.booleanProperty("If true, also flag any entry whose (x,y-1,z) is air/unloaded (floating with nothing underneath). Defaults to false.")
            ),
            java.util.List.of("world", "blocks")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        Object blocksObj = call.getArguments().get("blocks");
        String worldUuidStr = getArgumentAsString(call, "world");
        boolean checkSupport = getArgumentAsBoolean(call, "checkSupport");

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
                JsonArray wrong = new JsonArray();
                JsonArray unsupported = new JsonArray();
                int correctCount = 0;

                for (int i = 0; i < blocks.size(); i++) {
                    JsonObject blockData = blocks.get(i).getAsJsonObject();

                    int x = blockData.get("x").getAsInt();
                    int y = blockData.get("y").getAsInt();
                    int z = blockData.get("z").getAsInt();
                    String expected = blockData.get("blockType").getAsString();

                    WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                    BlockType actualType = world.getBlockType(x, y, z);

                    String actualId = (chunk == null || actualType == null)
                            ? null
                            : (actualType == BlockType.EMPTY ? "AIR" : actualType.getId());

                    boolean matches = actualId != null && actualId.equals(expected);
                    if (matches) {
                        correctCount++;
                    } else {
                        JsonObject mismatch = new JsonObject();
                        mismatch.addProperty("x", x);
                        mismatch.addProperty("y", y);
                        mismatch.addProperty("z", z);
                        mismatch.addProperty("expected", expected);
                        mismatch.addProperty("actual", actualId == null ? "UNLOADED" : actualId);
                        wrong.add(mismatch);
                    }

                    if (checkSupport) {
                        BlockType belowType = world.getBlockType(x, y - 1, z);
                        boolean supported = belowType != null && belowType != BlockType.EMPTY;
                        if (!supported) {
                            JsonObject floating = new JsonObject();
                            floating.addProperty("x", x);
                            floating.addProperty("y", y);
                            floating.addProperty("z", z);
                            unsupported.add(floating);
                        }
                    }
                }

                JsonObject response = new JsonObject();
                response.addProperty("total", blocks.size());
                response.addProperty("correct", correctCount);
                response.add("wrong", wrong);
                if (checkSupport) {
                    response.add("unsupported", unsupported);
                }

                logger.atInfo().log("[VERIFY_PLACEMENT] Checked " + blocks.size() + " positions ("
                        + correctCount + " correct, " + wrong.size() + " wrong"
                        + (checkSupport ? ", " + unsupported.size() + " unsupported" : "") + ")");

                future.complete(McpToolResponse.success(GSON.toJson(response)));

            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[VERIFY_PLACEMENT] Exception");
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

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }

    private boolean getArgumentAsBoolean(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }
}
