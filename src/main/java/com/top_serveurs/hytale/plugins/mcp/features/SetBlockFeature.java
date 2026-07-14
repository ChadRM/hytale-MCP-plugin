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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SetBlockFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public SetBlockFeature(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "set_block";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "set_block",
                "Sets a block at specified world coordinates. Optional 'rotation' controls facing for directional blocks (fences, stairs, etc) - one of None/Ninety/OneEighty/TwoSeventy, a 90-degree yaw step; defaults to None. ",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "x", McpToolSchema.integerProperty("X coordinate"),
                "y", McpToolSchema.integerProperty("Y coordinate"),
                "z", McpToolSchema.integerProperty("Z coordinate"),
                "blockType", McpToolSchema.stringProperty("Block type identifier"),
                "rotation", McpToolSchema.stringProperty("Optional yaw rotation for directional blocks: None, Ninety, OneEighty, or TwoSeventy. Defaults to None."),
                "world", McpToolSchema.stringProperty("World UUID")
            ),
            java.util.List.of("x", "y", "z", "blockType", "world")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {

        int x = getArgumentAsInt(call, "x");
        int y = getArgumentAsInt(call, "y");
        int z = getArgumentAsInt(call, "z");
        String blockTypeStr = getArgumentAsString(call, "blockType");
        String worldUuidStr = getArgumentAsString(call, "world");
        String rotationStr = getArgumentAsString(call, "rotation");

        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            return McpToolResponse.error("x, y and z are required integers");
        }

        if (blockTypeStr == null || blockTypeStr.isEmpty()) {
            return McpToolResponse.error("blockType is required");
        }

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
        }

        BlockType blockType = BlockType.getAssetMap().getAsset(blockTypeStr);
        if (blockType == null || blockType == BlockType.EMPTY) {
            return McpToolResponse.error("Unknown block type: " + blockTypeStr);
        }

        Rotation rotation = parseRotation(rotationStr);
        if (rotation == null) {
            return McpToolResponse.error("Invalid rotation: " + rotationStr + " (expected None, Ninety, OneEighty, or TwoSeventy)");
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
                logger.atInfo().log(
                        "[SET_BLOCK] Using WORLD API at (" + x + "," + y + "," + z + ")"
                );

                // world.setBlock's default IChunkAccessorSync implementation hardcodes rotation
                // to None - the only way to set a non-default facing is BlockAccessor.placeBlock
                // on the chunk directly, which takes yaw/pitch/roll Rotation values.
                WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                if (chunk == null) {
                    future.complete(McpToolResponse.error("Chunk not loaded at (" + x + "," + y + "," + z + ")"));
                    return;
                }
                // The 8-arg placeBlock convenience overload always runs with an internal
                // test=true occupancy check (testPlaceBlock), which rejects placement over any
                // already-solid block and returns false without writing - previously worked
                // around by breaking to air first. The 7-arg overload exposes the test flag
                // directly; passing test=false skips the occupancy check and writes straight
                // through for both air and occupied targets in one call, no break needed.
                RotationTuple rotationTuple = RotationTuple.of(rotation, Rotation.None, Rotation.None);
                boolean placed = chunk.placeBlock(x, y, z, blockType.getId(), rotationTuple, 0, false);

                if (!placed) {
                    future.complete(McpToolResponse.error("placeBlock rejected the placement at (" + x + "," + y + "," + z + ")"));
                    return;
                }

                JsonObject json = new JsonObject();
                json.addProperty("x", x);
                json.addProperty("y", y);
                json.addProperty("z", z);
                json.addProperty("blockType", blockTypeStr);
                json.addProperty("rotation", rotation.name());

                future.complete(McpToolResponse.success(GSON.toJson(json)));

            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[SET_BLOCK] Exception");
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

    static Rotation parseRotation(String rotationStr) {
        if (rotationStr == null || rotationStr.isEmpty()) {
            return Rotation.None;
        }
        try {
            return Rotation.valueOf(rotationStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
