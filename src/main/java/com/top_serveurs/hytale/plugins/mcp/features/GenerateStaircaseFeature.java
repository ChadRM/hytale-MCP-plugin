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
 * Pure geometry computation (no world access, no chunk loading) that generates a block staircase
 * ascending from a base landing in one of the four cardinal directions. Forward vectors match the
 * NORTH=-Z/SOUTH=+Z/EAST=+X/WEST=-X convention already established elsewhere in this project family
 * (see hytale-block-mod's Facing.java) rather than inventing a new one.
 *
 * <p>Solid by default: every step's column is filled from the base landing height up to that step's
 * own surface, so the result is a self-supporting ascending block mass with no floating overhangs -
 * the same "safe by default" posture generate_cylinder/generate_sphere take with their hollow flag.
 * Set hollow:true to place only each step's surface block instead (thin floating treads).
 *
 * <p>This is a planning tool only - it returns a block list, it does not write to the world. Pass the
 * result straight to set_blocks_batch, then verify_placement afterward.
 */
public class GenerateStaircaseFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private static final Map<String, int[]> FORWARD_VECTORS = Map.of(
            "North", new int[]{0, -1},
            "South", new int[]{0, 1},
            "East", new int[]{1, 0},
            "West", new int[]{-1, 0}
    );

    private final HytaleLogger logger;
    private final McpConfig config;

    public GenerateStaircaseFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "generate_staircase";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "generate_staircase",
                "Computes a block staircase block plan ascending from a base landing in one cardinal "
                        + "direction (North/South/East/West) - pure geometry, does not touch the world. Solid by "
                        + "default: each step's column is backfilled from the base landing height up to that "
                        + "step's own surface, so the result never has a floating overhang. Set hollow:true to "
                        + "place only each step's surface tread instead. Returns a flat blocks array ready to "
                        + "pass directly to set_blocks_batch, then verify_placement.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        var baseSchema = McpToolSchema.objectProperty(
            Map.of(
                "x", McpToolSchema.integerProperty("Base landing X coordinate"),
                "y", McpToolSchema.integerProperty("Base landing Y coordinate - the floor the first step rises from"),
                "z", McpToolSchema.integerProperty("Base landing Z coordinate")
            ),
            List.of("x", "y", "z"),
            "The floor/landing position the staircase rises from - step 1 begins one block forward of here"
        );

        return McpToolSchema.schemaWithProperties(
            Map.of(
                "base", baseSchema,
                "direction", McpToolSchema.stringProperty("Cardinal direction the staircase ascends toward: North, South, East, or West"),
                "steps", McpToolSchema.integerProperty("Number of steps"),
                "width", McpToolSchema.integerProperty("Width in blocks perpendicular to the direction of travel, centered on the base position"),
                "blockType", McpToolSchema.stringProperty("Block type identifier for the staircase"),
                "stepDepth", McpToolSchema.integerProperty("Horizontal blocks of run per step, in the direction of travel. Defaults to 1."),
                "stepHeight", McpToolSchema.integerProperty("Vertical rise in blocks per step. Defaults to 1."),
                "hollow", McpToolSchema.booleanProperty("If true, place only each step's surface tread instead of backfilling solid down to the base landing height. Defaults to false (solid, no floating overhangs).")
            ),
            List.of("base", "direction", "steps", "width", "blockType")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        Object baseObj = call.getArguments().get("base");
        String direction = getArgumentAsString(call, "direction");
        int steps = getArgumentAsInt(call, "steps");
        int width = getArgumentAsInt(call, "width");
        String blockType = getArgumentAsString(call, "blockType");
        int stepDepth = getArgumentAsInt(call, "stepDepth");
        if (stepDepth == Integer.MIN_VALUE) stepDepth = 1;
        int stepHeight = getArgumentAsInt(call, "stepHeight");
        if (stepHeight == Integer.MIN_VALUE) stepHeight = 1;
        boolean hollow = getArgumentAsBoolean(call, "hollow");

        if (baseObj == null) {
            return McpToolResponse.error("base is required");
        }
        int[] forward = direction != null ? FORWARD_VECTORS.get(capitalize(direction)) : null;
        if (forward == null) {
            return McpToolResponse.error("direction must be one of North, South, East, West");
        }
        if (steps == Integer.MIN_VALUE || steps < 1) {
            return McpToolResponse.error("steps must be a positive integer");
        }
        if (width == Integer.MIN_VALUE || width < 1) {
            return McpToolResponse.error("width must be a positive integer");
        }
        if (blockType == null) {
            return McpToolResponse.error("blockType is required");
        }
        if (stepDepth < 1) {
            return McpToolResponse.error("stepDepth must be a positive integer");
        }
        if (stepHeight < 1) {
            return McpToolResponse.error("stepHeight must be a positive integer");
        }

        JsonObject base;
        try {
            base = GSON.toJsonTree(baseObj).getAsJsonObject();
        } catch (Exception e) {
            return McpToolResponse.error("Invalid base format: " + e.getMessage());
        }
        int bx = base.get("x").getAsInt();
        int by = base.get("y").getAsInt();
        int bz = base.get("z").getAsInt();

        int perpX = -forward[1];
        int perpZ = forward[0];
        int halfWidthLow = (width - 1) / 2;

        int maxBlocks = config.getFeatures().getMaxBlocksBatch();
        long worstCase = (long) steps * stepDepth * width * (hollow ? 1 : (long) steps * stepHeight);
        if (worstCase > maxBlocks * 8L) {
            return McpToolResponse.error("steps " + steps + " / width " + width + " is too large to evaluate in "
                    + "a single call - use fewer steps, a narrower width, or hollow:true.");
        }

        // Keyed by "x,y,z" so a step's backfill never double-emits a cell another step's backfill also covers.
        Map<String, int[]> cells = new LinkedHashMap<>();

        for (int i = 0; i < steps; i++) {
            int stepY = by + (i + 1) * stepHeight;
            for (int d = 0; d < stepDepth; d++) {
                int forwardDist = i * stepDepth + d + 1;
                for (int j = 0; j < width; j++) {
                    int offset = j - halfWidthLow;
                    int x = bx + forward[0] * forwardDist + perpX * offset;
                    int z = bz + forward[1] * forwardDist + perpZ * offset;

                    if (hollow) {
                        cells.putIfAbsent(x + "," + stepY + "," + z, new int[]{x, stepY, z});
                    } else {
                        for (int y = by + 1; y <= stepY; y++) {
                            cells.putIfAbsent(x + "," + y + "," + z, new int[]{x, y, z});
                        }
                    }

                    if (cells.size() > maxBlocks) {
                        return McpToolResponse.error("Generated staircase exceeds the " + maxBlocks
                                + "-block set_blocks_batch limit - use fewer steps, a narrower width, or hollow:true.");
                    }
                }
            }
        }

        JsonArray blocks = new JsonArray();
        for (int[] xyz : cells.values()) {
            JsonObject block = new JsonObject();
            block.addProperty("x", xyz[0]);
            block.addProperty("y", xyz[1]);
            block.addProperty("z", xyz[2]);
            block.addProperty("blockType", blockType);
            blocks.add(block);
        }

        JsonObject response = new JsonObject();
        response.addProperty("total", blocks.size());
        response.addProperty("hollow", hollow);
        response.add("blocks", blocks);

        logger.atInfo().log("[GENERATE_STAIRCASE] Generated " + blocks.size() + " blocks (" + steps
                + " steps, direction " + direction + ", hollow=" + hollow + ") from base (" + bx + "," + by + "," + bz + ")");

        return McpToolResponse.success(GSON.toJson(response));
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
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
