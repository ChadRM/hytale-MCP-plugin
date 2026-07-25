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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure geometry computation (no world access, no chunk loading) that generates a tall thin lattice
 * tower - vertical corner posts evenly spaced around a circle, with optional horizontal ring braces
 * connecting them at regular height intervals. Motivated by the Discworld Clacks tower shape
 * (hytale-block-mod), which is exactly this kind of open lattice structure rather than a solid form.
 *
 * <p>This is a planning tool only - it returns a block list, it does not write to the world. Pass the
 * result straight to set_blocks_batch, then verify_placement afterward. Diagonal/X cross-bracing is
 * deliberately not included in this first version - only vertical posts and horizontal rings.
 */
public class GenerateLatticeColumnFeature implements McpFeature {

    private static final Gson GSON = new Gson();

    private final HytaleLogger logger;
    private final McpConfig config;

    public GenerateLatticeColumnFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "generate_lattice_column";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "generate_lattice_column",
                "Computes a tall thin lattice tower block plan - postCount vertical corner posts evenly "
                        + "spaced around a circle of the given radius, rising from a base center point, plus "
                        + "optional horizontal ring braces connecting adjacent posts every braceInterval blocks "
                        + "of height. Pure geometry, does not touch the world. Good for open lattice/truss-style "
                        + "towers (e.g. a Discworld Clacks semaphore tower) as opposed to a solid cylinder. "
                        + "Diagonal cross-bracing is not included in this version, only vertical posts and "
                        + "horizontal rings. Returns a flat blocks array ready to pass directly to "
                        + "set_blocks_batch, then verify_placement.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        var centerSchema = McpToolSchema.objectProperty(
            Map.of(
                "x", McpToolSchema.integerProperty("Base center X coordinate"),
                "y", McpToolSchema.integerProperty("Base Y coordinate - the tower rises from here"),
                "z", McpToolSchema.integerProperty("Base center Z coordinate")
            ),
            List.of("x", "y", "z"),
            "The tower's base center point (posts are placed around this axis, not on it)"
        );

        return McpToolSchema.schemaWithProperties(
            Map.of(
                "center", centerSchema,
                "height", McpToolSchema.integerProperty("Tower height in blocks, rising from the base Y"),
                "radius", McpToolSchema.integerProperty("Distance in blocks from the center axis to each corner post"),
                "postCount", McpToolSchema.integerProperty("Number of vertical corner posts evenly spaced around the circle (e.g. 4 for a square lattice tower)"),
                "postBlockType", McpToolSchema.stringProperty("Block type identifier for the vertical corner posts"),
                "braceBlockType", McpToolSchema.stringProperty("Optional: block type identifier for horizontal ring braces connecting adjacent posts. Omit for posts only, no bracing."),
                "braceInterval", McpToolSchema.integerProperty("Height interval in blocks between horizontal ring braces (e.g. 4 = a ring every 4 blocks). Required if braceBlockType is given; a ring is always placed at the base (y=0) as well.")
            ),
            List.of("center", "height", "radius", "postCount", "postBlockType")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        Object centerObj = call.getArguments().get("center");
        int height = getArgumentAsInt(call, "height");
        int radius = getArgumentAsInt(call, "radius");
        int postCount = getArgumentAsInt(call, "postCount");
        String postBlockType = getArgumentAsString(call, "postBlockType");
        String braceBlockType = getArgumentAsString(call, "braceBlockType");
        int braceInterval = getArgumentAsInt(call, "braceInterval");

        if (centerObj == null) {
            return McpToolResponse.error("center is required");
        }
        if (height == Integer.MIN_VALUE || height < 1) {
            return McpToolResponse.error("height must be a positive integer");
        }
        if (radius == Integer.MIN_VALUE || radius < 1) {
            return McpToolResponse.error("radius must be a positive integer");
        }
        if (postCount == Integer.MIN_VALUE || postCount < 3) {
            return McpToolResponse.error("postCount must be an integer >= 3");
        }
        if (postBlockType == null) {
            return McpToolResponse.error("postBlockType is required");
        }
        if (braceBlockType != null && (braceInterval == Integer.MIN_VALUE || braceInterval < 1)) {
            return McpToolResponse.error("braceInterval must be a positive integer when braceBlockType is given");
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

        int maxBlocks = config.getFeatures().getMaxBlocksBatch();

        // Post positions around the circle, rounded to integer block coordinates.
        int[] postX = new int[postCount];
        int[] postZ = new int[postCount];
        for (int i = 0; i < postCount; i++) {
            double angle = 2 * Math.PI * i / postCount;
            postX[i] = cx + (int) Math.round(radius * Math.cos(angle));
            postZ[i] = cz + (int) Math.round(radius * Math.sin(angle));
        }

        // Keyed by "x,y,z" so a brace ring cell that coincides with a post never produces a duplicate entry.
        Map<String, int[]> cells = new LinkedHashMap<>(); // "x,y,z" -> {x,y,z}
        int postBlockCount = 0;
        int braceBlockCount = 0;

        for (int i = 0; i < postCount; i++) {
            for (int dy = 0; dy < height; dy++) {
                String key = postX[i] + "," + (baseY + dy) + "," + postZ[i];
                if (cells.putIfAbsent(key, new int[]{postX[i], baseY + dy, postZ[i]}) == null) {
                    postBlockCount++;
                }
            }
        }

        if (braceBlockType != null) {
            for (int dy = 0; dy < height; dy += braceInterval) {
                int y = baseY + dy;
                for (int i = 0; i < postCount; i++) {
                    int j = (i + 1) % postCount;
                    for (int[] xz : line2D(postX[i], postZ[i], postX[j], postZ[j])) {
                        String key = xz[0] + "," + y + "," + xz[1];
                        if (!cells.containsKey(key)) {
                            cells.put(key, new int[]{xz[0], y, xz[1]});
                            braceBlockCount++;
                        }
                    }
                }
            }
        }

        if (cells.size() > maxBlocks) {
            return McpToolResponse.error("Generated lattice column has " + cells.size() + " cells, exceeding "
                    + "the " + maxBlocks + "-block set_blocks_batch limit - use a smaller radius/height/postCount, "
                    + "or a larger braceInterval.");
        }

        JsonArray blocks = new JsonArray();
        Map<String, Boolean> isPost = new LinkedHashMap<>();
        for (int i = 0; i < postCount; i++) {
            for (int dy = 0; dy < height; dy++) {
                isPost.put(postX[i] + "," + (baseY + dy) + "," + postZ[i], true);
            }
        }
        for (Map.Entry<String, int[]> entry : cells.entrySet()) {
            int[] xyz = entry.getValue();
            boolean post = isPost.containsKey(entry.getKey());

            JsonObject block = new JsonObject();
            block.addProperty("x", xyz[0]);
            block.addProperty("y", xyz[1]);
            block.addProperty("z", xyz[2]);
            block.addProperty("blockType", post ? postBlockType : braceBlockType);
            block.addProperty("role", post ? "post" : "brace");
            blocks.add(block);
        }

        JsonObject response = new JsonObject();
        response.addProperty("total", blocks.size());
        response.addProperty("postBlockCount", postBlockCount);
        response.addProperty("braceBlockCount", braceBlockCount);
        response.add("blocks", blocks);

        logger.atInfo().log("[GENERATE_LATTICE_COLUMN] Generated " + blocks.size() + " blocks (" + postCount
                + " posts, height " + height + ", radius " + radius + ") based at (" + cx + "," + baseY + "," + cz + ")");

        return McpToolResponse.success(GSON.toJson(response));
    }

    /** Integer 2D line between two points via a simple DDA walk, returns points inclusive of both ends. */
    private static List<int[]> line2D(int x0, int z0, int x1, int z1) {
        List<int[]> points = new java.util.ArrayList<>();
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        if (steps == 0) {
            points.add(new int[]{x0, z0});
            return points;
        }
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int x = (int) Math.round(x0 + (x1 - x0) * t);
            int z = (int) Math.round(z0 + (z1 - z0) * t);
            points.add(new int[]{x, z});
        }
        return points;
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
