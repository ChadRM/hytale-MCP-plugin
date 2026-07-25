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
 * Pure geometry computation (no world access, no chunk loading) that generates a sphere or hollow
 * spherical shell as a block coordinate list. Exists to replace the kind of one-off hand-derived
 * shell-geometry script (round(distance from center) == radius) that building a hollow sphere used
 * to require - see hytale-block-mod's project memory for the diameter-13 sphere that motivated this.
 *
 * <p>This is a planning tool only - it returns a block list, it does not write to the world. Pass the
 * result straight to set_blocks_batch, then verify_placement afterward.
 */
public class GenerateSphereFeature implements McpFeature {

    private static final Gson GSON = new Gson();

    private final HytaleLogger logger;
    private final McpConfig config;

    public GenerateSphereFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "generate_sphere";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "generate_sphere",
                "Computes a sphere or hollow spherical shell block plan around a center point - pure "
                        + "geometry, does not touch the world. A block is included if its distance from the "
                        + "center is within the requested radius (solid) or within shellThickness of the "
                        + "surface (hollow). Returns a flat blocks array ready to pass directly to "
                        + "set_blocks_batch, then verify_placement.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        var centerSchema = McpToolSchema.objectProperty(
            Map.of(
                "x", McpToolSchema.integerProperty("Center X coordinate"),
                "y", McpToolSchema.integerProperty("Center Y coordinate"),
                "z", McpToolSchema.integerProperty("Center Z coordinate")
            ),
            List.of("x", "y", "z"),
            "The sphere's center point"
        );

        return McpToolSchema.schemaWithProperties(
            Map.of(
                "center", centerSchema,
                "radius", McpToolSchema.integerProperty("Sphere radius in blocks (use the same value for a hollow shell's outer radius)"),
                "blockType", McpToolSchema.stringProperty("Block type identifier to fill the sphere/shell with"),
                "hollow", McpToolSchema.booleanProperty("If true, only generate a shell near the surface instead of a solid ball. Defaults to false (solid)."),
                "shellThickness", McpToolSchema.integerProperty("Shell thickness in blocks when hollow is true. Defaults to 1 (a single-voxel-thick shell). Ignored when hollow is false.")
            ),
            List.of("center", "radius", "blockType")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        Object centerObj = call.getArguments().get("center");
        int radius = getArgumentAsInt(call, "radius");
        String blockType = getArgumentAsString(call, "blockType");
        boolean hollow = getArgumentAsBoolean(call, "hollow");
        int shellThickness = getArgumentAsInt(call, "shellThickness");
        if (shellThickness == Integer.MIN_VALUE) shellThickness = 1;

        if (centerObj == null) {
            return McpToolResponse.error("center is required");
        }
        if (radius == Integer.MIN_VALUE || radius < 1) {
            return McpToolResponse.error("radius must be a positive integer");
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
        int cy = center.get("y").getAsInt();
        int cz = center.get("z").getAsInt();

        long candidateCount = (long) Math.pow(2 * radius + 1, 3);
        int maxBlocks = config.getFeatures().getMaxBlocksBatch();
        if (candidateCount > maxBlocks * 8L) {
            return McpToolResponse.error("radius " + radius + " bounding cube covers " + candidateCount
                    + " candidate positions - too large to evaluate in a single call. Use a smaller radius.");
        }

        JsonArray blocks = new JsonArray();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - radius; y <= cy + radius; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    double dist = Math.sqrt(Math.pow(x - cx, 2) + Math.pow(y - cy, 2) + Math.pow(z - cz, 2));
                    boolean include = hollow
                            ? (dist <= radius && dist > radius - shellThickness)
                            : dist <= radius;
                    if (!include) continue;

                    JsonObject block = new JsonObject();
                    block.addProperty("x", x);
                    block.addProperty("y", y);
                    block.addProperty("z", z);
                    block.addProperty("blockType", blockType);
                    blocks.add(block);

                    if (blocks.size() > maxBlocks) {
                        return McpToolResponse.error("Generated sphere exceeds the " + maxBlocks
                                + "-block set_blocks_batch limit - use a smaller radius, or a thinner "
                                + "shell if hollow.");
                    }
                }
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("total", blocks.size());
        response.addProperty("hollow", hollow);
        response.add("blocks", blocks);

        logger.atInfo().log("[GENERATE_SPHERE] Generated " + blocks.size() + " blocks (radius " + radius
                + ", hollow=" + hollow + ") centered at (" + cx + "," + cy + "," + cz + ")");

        return McpToolResponse.success(GSON.toJson(response));
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        // Pure computation, no world access - gate at the same level as scan_region/generate_road_corridor
        // (read-only tier) rather than requiring a new permission flag/live config edit.
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
