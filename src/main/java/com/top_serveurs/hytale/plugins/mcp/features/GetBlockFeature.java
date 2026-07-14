package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
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

public class GetBlockFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public GetBlockFeature(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "get_block";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "get_block",
                "Gets the block type actually placed at specific world coordinates - the read counterpart to set_block. Also reports fluid presence/type/level, which is tracked separately from block type and would otherwise read as plain air.",
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
                "world", McpToolSchema.stringProperty("World UUID")
            ),
            java.util.List.of("x", "y", "z", "world")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {

        int x = getArgumentAsInt(call, "x");
        int y = getArgumentAsInt(call, "y");
        int z = getArgumentAsInt(call, "z");
        String worldUuidStr = getArgumentAsString(call, "world");

        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            return McpToolResponse.error("x, y and z are required integers");
        }

        if (worldUuidStr == null) {
            return McpToolResponse.error("world UUID is required");
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
                // getBlockType returns null when the target chunk isn't loaded (not an error
                // condition, just unresolvable right now) - distinct from BlockType.EMPTY, which
                // means the chunk is loaded and the position is genuinely air.
                BlockType blockType = world.getBlockType(x, y, z);
                WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));

                JsonObject json = new JsonObject();
                json.addProperty("x", x);
                json.addProperty("y", y);
                json.addProperty("z", z);

                if (blockType == null || chunk == null) {
                    json.addProperty("loaded", false);
                    json.add("blockType", null);
                } else if (blockType == BlockType.EMPTY) {
                    json.addProperty("loaded", true);
                    json.addProperty("air", true);
                    json.add("blockType", null);
                    addFluidInfo(json, chunk, x, y, z);
                } else {
                    json.addProperty("loaded", true);
                    json.addProperty("air", false);
                    json.addProperty("blockType", blockType.getId());
                    addFluidInfo(json, chunk, x, y, z);
                }

                future.complete(McpToolResponse.success(GSON.toJson(json)));

            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[GET_BLOCK] Exception");
                future.complete(McpToolResponse.error(t.toString()));
            }
        });

        return future.join();
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canGetBlock();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canGetBlock();
        }
        return false;
    }

    /**
     * Fluid presence/type/level lives on a separate data channel from {@code BlockType} in this
     * engine - a position can read as plain air via {@code getBlockType} while still being full of
     * water, since fluids are tracked per-chunk via {@code WorldChunk.getFluidId}/{@code
     * getFluidLevel} rather than occupying a distinct {@code BlockType}. Confirmed the hard way: a
     * player standing in visibly 2-block-deep water had {@code get_block} report plain air at their
     * feet before this was added.
     */
    private void addFluidInfo(JsonObject json, WorldChunk chunk, int x, int y, int z) {
        int fluidId = chunk.getFluidId(x, y, z);
        boolean hasFluid = fluidId != Fluid.EMPTY_ID;
        json.addProperty("hasFluid", hasFluid);
        if (hasFluid) {
            Fluid fluid = Fluid.getAssetMap().getAsset(fluidId);
            json.addProperty("fluidType", fluid != null ? fluid.getId() : null);
            json.addProperty("fluidLevel", chunk.getFluidLevel(x, y, z));
        }
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
