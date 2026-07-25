package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.List;
import java.util.Map;

/**
 * Pure geometry computation (no world access, no chunk loading) that generates a vertical cylinder
 * or hollow cylindrical shell (a tower/pillar/silo shape) as a block coordinate list. Only a
 * vertical (Y-axis) orientation is supported - every real use case so far (towers, pillars, wells)
 * has been vertical; a horizontal variant can be added later if one is actually needed.
 *
 * <p>This is a planning tool only - it returns a block list, it does not write to the world. Pass the
 * result straight to set_blocks_batch, then verify_placement afterward.
 */
public class GenerateCylinderFeature implements McpFeature {

    private static final Gson GSON = new Gson();

    private final HytaleLogger logger;
    private final McpConfig config;

    public GenerateCylinderFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "generate_cylinder";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "generate_cylinder",
                "Computes a vertical cylinder or hollow cylindrical shell block plan (tower/pillar/silo "
                        + "shape) rising from a base center point - pure geometry, does not touch the world. "
                        + "Only vertical (Y-axis) orientation is supported. A column is included at radius r "
                        + "from the axis if r is within the requested radius (solid) or within shellThickness "
                        + "of the outer surface (hollow); capBottom/capTop optionally fill the end discs solid "
                        + "even when hollow. Returns a flat blocks array ready to pass directly to "
                        + "set_blocks_batch, then verify_placement.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        var centerSchema = McpToolSchema.objectProperty(
            Map.of(
                "x", McpToolSchema.integerProperty("Base center X coordinate"),
                "y", McpToolSchema.integerProperty("Base (bottom) Y coordinate - the cylinder rises from here"),
                "z", McpToolSchema.integerProperty("Base center Z coordinate")
            ),
            List.of("x", "y", "z"),
            "The cylinder's base (bottom) center point"
        );

        return McpToolSchema.schemaWithProperties(
            Map.of(
                "center", centerSchema,
                "radius", McpToolSchema.integerProperty("Cylinder radius in blocks"),
                "height", McpToolSchema.integerProperty("Cylinder height in blocks, rising from the base Y"),
                "blockType", McpToolSchema.stringProperty("Block type identifier to fill the cylinder/shell with"),
                "hollow", McpToolSchema.booleanProperty("If true, only generate the outer wall instead of a solid cylinder. Defaults to false (solid)."),
                "shellThickness", McpToolSchema.integerProperty("Wall thickness in blocks when hollow is true. Defaults to 1. Ignored when hollow is false."),
                "capBottom", McpToolSchema.booleanProperty("If true, fill the bottom disc solid even when hollow. Defaults to false."),
                "capTop", McpToolSchema.booleanProperty("If true, fill the top disc solid even when hollow. Defaults to false.")
            ),
            List.of("center", "radius", "height", "blockType")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        Object centerObj = call.getArguments().get("center");
        int radius = getArgumentAsInt(call, "radius");
        int height = getArgumentAsInt(call, "height");
        String blockType = getArgumentAsString(call, "blockType");
        boolean hollow = getArgumentAsBoolean(call, "hollow");
        int shellThickness = getArgumentAsInt(call, "shellThickness");
        if (shellThickness == Integer.MIN_VALUE) shellThickness = 1;
        boolean capBottom = getArgumentAsBoolean(call, "capBottom");
        boolean capTop = getArgumentAsBoolean(call, "capTop");

        if (centerObj == null) {
            return McpToolResponse.error("center is required");
        }
        if (radius == Integer.MIN_VALUE || radius < 1) {
            return McpToolResponse.error("radius must be a positive integer");
        }
        if (height == Integer.MIN_VALUE || height < 1) {
            return McpToolResponse.error("height must be a positive integer");
        }
        if (blockType == null) {
            return McpToolResponse.error("blockType is required");
        }
        if (hollow && shellThickness < 1) {
            return McpToolResponse.error("shellThickness must be a positive integer when hollow is true");
        }

        JsonObject center;
        try {
            center = GSON.toJsonTree(centerObj).getAsJsonObject();
        } catch (Exception e) {
            return McpToolResponse.error("Invalid center format: " + e.getMessage());
        }
        int cx = center.get("x").getAsInt();
        int baseY = center.get("y").getAsInt();
        int cz = center.get("z").getAsInt();

        long candidateCount = (long) Math.pow(2 * radius + 1, 2) * height;
        int maxBlocks = config.getFeatures().getMaxBlocksBatch();
        if (candidateCount > maxBlocks * 8L) {
            return McpToolResponse.error("radius " + radius + " / height " + height + " covers " + candidateCount
                    + " candidate positions - too large to evaluate in a single call. Use a smaller radius/height.");
        }

        JsonArray blocks = new JsonArray();
        for (int dy = 0; dy < height; dy++) {
            int y = baseY + dy;
            boolean solidDisc = (dy == 0 && capBottom) || (dy == height - 1 && capTop);
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    double r = Math.hypot(x - cx, z - cz);
                    boolean include = (hollow && !solidDisc)
                            ? (r <= radius && r > radius - shellThickness)
                            : r <= radius;
                    if (!include) continue;

                    JsonObject block = new JsonObject();
                    block.addProperty("x", x);
                    block.addProperty("y", y);
                    block.addProperty("z", z);
                    block.addProperty("blockType", blockType);
                    blocks.add(block);

                    if (blocks.size() > maxBlocks) {
                        return McpToolResponse.error("Generated cylinder exceeds the " + maxBlocks
                                + "-block set_blocks_batch limit - use a smaller radius/height, or a thinner "
                                + "shell if hollow.");
                    }
                }
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("total", blocks.size());
        response.addProperty("hollow", hollow);
        response.add("blocks", blocks);

        logger.atInfo().log("[GENERATE_CYLINDER] Generated " + blocks.size() + " blocks (radius " + radius
                + ", height " + height + ", hollow=" + hollow + ") based at (" + cx + "," + baseY + "," + cz + ")");

        return McpToolResponse.success(GSON.toJson(response));
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

    private boolean getArgumentAsBoolean(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }
}
