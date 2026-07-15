package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * Pure geometry computation (no world access, no chunk loading) that generates a road/path corridor
 * block plan from a waypoint chain. Exists specifically to replace hand-derived road-geometry math
 * that was re-derived live, under pressure, multiple times in one hytale-block-mod session - including
 * a real bug (see that project's project_road_building_rules memory, "Pythagoras nails us" section)
 * where elevation was keyed to a grid axis (z) instead of true forward-progress along a diagonal
 * segment, producing a road with an uneven left-right cross-section and locally-too-steep grade.
 *
 * <p>This tool gets that geometry right once:
 * <ul>
 *   <li>Width is a perpendicular Euclidean distance from the segment (not a grid-axis offset), so a
 *       diagonal segment gets a properly angled band, not a staircase.</li>
 *   <li>Elevation is a function of each cell's own projected position along the segment (t, 0..1),
 *       so every cell at the same forward-progress value gets the identical Y - a level cross-section
 *       perpendicular to true direction of travel, on any segment orientation.</li>
 *   <li>Grade is validated per segment against the standard 1-block-rise-per-2-blocks-horizontal-
 *       distance rule before any blocks are generated; segments that would require a steeper grade are
 *       rejected with the minimum compliant distance, rather than silently producing an over-steep
 *       road.</li>
 * </ul>
 *
 * <p>Multi-segment waypoint chains are supported; a cell whose perpendicular distance qualifies it for
 * more than one segment (e.g. near a shared waypoint) is assigned to whichever segment it is closest
 * to, avoiding duplicate/conflicting entries at joints.
 *
 * <p>This is a planning tool only - it returns a block list, it does not write to the world. Pass the
 * result straight to set_blocks_batch, then verify_placement afterward.
 */
public class GenerateRoadCorridorFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private static final double MAX_GRADE_RATIO = 0.5; // 1 block of rise per 2 blocks of horizontal travel

    private final HytaleLogger logger;
    private final McpConfig config;

    public GenerateRoadCorridorFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
        this.config = config;
    }

    @Override
    public String getName() {
        return "generate_road_corridor";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "generate_road_corridor",
                "Computes a road/path block plan from a chain of waypoints - pure geometry, does not touch the "
                        + "world. Handles cardinal, diagonal, or any-angle segments correctly: width is measured as true "
                        + "perpendicular distance from the segment (not a grid-axis offset), and elevation is assigned "
                        + "per-cell from its own projected forward-progress along the segment, so every true left-right "
                        + "cross-section comes out level regardless of segment angle. Validates each segment against the "
                        + "standard grade rule (max 1 block of rise per 2 blocks of horizontal distance) up front and "
                        + "returns an error naming the offending segment instead of silently generating an over-steep "
                        + "road - add an intermediate waypoint or reduce the elevation change if that happens. Optionally "
                        + "adds a same-elevation shoulder ring on both sides. Returns a flat blocks array ready to pass "
                        + "directly to set_blocks_batch, then verify_placement.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        var waypointSchema = McpToolSchema.objectProperty(
            java.util.Map.of(
                "x", McpToolSchema.integerProperty("X coordinate"),
                "y", McpToolSchema.integerProperty("Y coordinate (elevation) at this waypoint"),
                "z", McpToolSchema.integerProperty("Z coordinate")
            ),
            java.util.List.of("x", "y", "z"),
            "A waypoint the corridor passes through; consecutive waypoints form straight segments"
        );

        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "waypoints", McpToolSchema.arrayProperty(waypointSchema, "Ordered list of 2+ waypoints; consecutive pairs form straight segments"),
                "width", McpToolSchema.integerProperty("Total path width in blocks (e.g. 3 for a cardinal road, 4-5 for a diagonal one)"),
                "blockType", McpToolSchema.stringProperty("Block type identifier for the path surface"),
                "shoulderWidth", McpToolSchema.integerProperty("Optional: width in blocks of a same-elevation shoulder ring on each side of the path. Omit or 0 for no shoulder."),
                "shoulderBlockType", McpToolSchema.stringProperty("Block type identifier for the shoulder ring. Required if shoulderWidth > 0.")
            ),
            java.util.List.of("waypoints", "width", "blockType")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        Object waypointsObj = call.getArguments().get("waypoints");
        int width = getArgumentAsInt(call, "width");
        String blockType = getArgumentAsString(call, "blockType");
        int shoulderWidth = getArgumentAsInt(call, "shoulderWidth");
        if (shoulderWidth == Integer.MIN_VALUE) shoulderWidth = 0;
        String shoulderBlockType = getArgumentAsString(call, "shoulderBlockType");

        if (waypointsObj == null) {
            return McpToolResponse.error("waypoints array is required");
        }
        if (width == Integer.MIN_VALUE || width < 1) {
            return McpToolResponse.error("width must be a positive integer");
        }
        if (blockType == null) {
            return McpToolResponse.error("blockType is required");
        }
        if (shoulderWidth > 0 && shoulderBlockType == null) {
            return McpToolResponse.error("shoulderBlockType is required when shoulderWidth > 0");
        }

        JsonArray waypointsArr;
        try {
            if (waypointsObj instanceof JsonArray) {
                waypointsArr = (JsonArray) waypointsObj;
            } else if (waypointsObj instanceof List) {
                waypointsArr = GSON.toJsonTree(waypointsObj).getAsJsonArray();
            } else {
                JsonElement element = GSON.toJsonTree(waypointsObj);
                if (element.isJsonArray()) {
                    waypointsArr = element.getAsJsonArray();
                } else {
                    return McpToolResponse.error("waypoints must be an array");
                }
            }
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error parsing waypoints array");
            return McpToolResponse.error("Invalid waypoints format: " + e.getMessage());
        }

        if (waypointsArr.size() < 2) {
            return McpToolResponse.error("At least 2 waypoints are required to form a segment");
        }

        double[][] waypoints = new double[waypointsArr.size()][3];
        for (int i = 0; i < waypointsArr.size(); i++) {
            JsonObject wp = waypointsArr.get(i).getAsJsonObject();
            waypoints[i][0] = wp.get("x").getAsDouble();
            waypoints[i][1] = wp.get("y").getAsDouble();
            waypoints[i][2] = wp.get("z").getAsDouble();
        }

        double halfWidth = width / 2.0;
        double outerHalfWidth = halfWidth + Math.max(0, shoulderWidth);

        // Validate grade on every segment before generating anything.
        for (int i = 0; i < waypoints.length - 1; i++) {
            double[] a = waypoints[i];
            double[] b = waypoints[i + 1];
            double horizontalDistance = Math.hypot(b[0] - a[0], b[2] - a[2]);
            double rise = Math.abs(b[1] - a[1]);

            if (horizontalDistance == 0) {
                if (rise > 0) {
                    return McpToolResponse.error("Segment " + i + " (waypoint " + i + " to " + (i + 1)
                            + ") has zero horizontal distance but a " + rise + "-block elevation change - "
                            + "that's a vertical wall, not a gradeable road segment.");
                }
                return McpToolResponse.error("Segment " + i + " (waypoint " + i + " to " + (i + 1)
                        + ") has zero length - waypoints " + i + " and " + (i + 1) + " are the same (x,z) position.");
            }

            double maxRise = horizontalDistance * MAX_GRADE_RATIO;
            if (rise > maxRise) {
                double minDistance = rise / MAX_GRADE_RATIO;
                return McpToolResponse.error(String.format(
                        "Segment %d (waypoint %d to %d) requires %.1f blocks of elevation change over only %.1f "
                                + "blocks of horizontal distance - exceeds the max grade of 1 block per 2 blocks "
                                + "traveled. Needs at least %.1f blocks of horizontal distance for that much rise; "
                                + "add an intermediate waypoint or reduce the elevation change.",
                        i, i, i + 1, rise, horizontalDistance, minDistance));
            }
        }

        // Bounding box across all waypoints, padded by the outer width.
        double minX = waypoints[0][0], maxX = waypoints[0][0];
        double minZ = waypoints[0][2], maxZ = waypoints[0][2];
        for (double[] wp : waypoints) {
            minX = Math.min(minX, wp[0]);
            maxX = Math.max(maxX, wp[0]);
            minZ = Math.min(minZ, wp[2]);
            maxZ = Math.max(maxZ, wp[2]);
        }
        int pad = (int) Math.ceil(outerHalfWidth) + 1;
        int loX = (int) Math.floor(minX) - pad;
        int hiX = (int) Math.ceil(maxX) + pad;
        int loZ = (int) Math.floor(minZ) - pad;
        int hiZ = (int) Math.ceil(maxZ) + pad;

        long candidateCount = (long) (hiX - loX + 1) * (hiZ - loZ + 1);
        int maxBlocks = config.getFeatures().getMaxBlocksBatch();
        if (candidateCount > maxBlocks * 4L) {
            return McpToolResponse.error("Waypoint span is too large for a single call (bounding box covers "
                    + candidateCount + " columns) - split the road into shorter waypoint chains and call this "
                    + "tool once per chain.");
        }

        // For each candidate column, find the closest segment and its projected t.
        Map<String, double[]> best = new LinkedHashMap<>(); // "x,z" -> {distance, y, isPath(1/0)}
        for (int x = loX; x <= hiX; x++) {
            for (int z = loZ; z <= hiZ; z++) {
                double cx = x + 0.5, cz = z + 0.5;
                double bestDist = Double.MAX_VALUE;
                double bestY = 0;

                for (int i = 0; i < waypoints.length - 1; i++) {
                    double[] a = waypoints[i];
                    double[] b = waypoints[i + 1];
                    double dx = b[0] - a[0], dz = b[2] - a[2];
                    double len2 = dx * dx + dz * dz;
                    double t = ((cx - a[0]) * dx + (cz - a[2]) * dz) / len2;
                    double tClamped = Math.max(0, Math.min(1, t));
                    double px = a[0] + tClamped * dx, pz = a[2] + tClamped * dz;
                    double dist = Math.hypot(cx - px, cz - pz);

                    if (dist < bestDist) {
                        bestDist = dist;
                        bestY = Math.round(a[1] + (b[1] - a[1]) * tClamped);
                    }
                }

                if (bestDist <= outerHalfWidth) {
                    best.put(x + "," + z, new double[]{bestDist, bestY});
                }
            }
        }

        if (best.size() > maxBlocks) {
            return McpToolResponse.error("Generated corridor has " + best.size() + " cells, exceeding the "
                    + maxBlocks + "-block set_blocks_batch limit - split the road into shorter waypoint chains.");
        }

        JsonArray blocks = new JsonArray();
        int pathCount = 0, shoulderCount = 0;
        for (Map.Entry<String, double[]> entry : best.entrySet()) {
            String[] xz = entry.getKey().split(",");
            double dist = entry.getValue()[0];
            int y = (int) entry.getValue()[1];
            boolean isPath = dist <= halfWidth;

            JsonObject block = new JsonObject();
            block.addProperty("x", Integer.parseInt(xz[0]));
            block.addProperty("y", y);
            block.addProperty("z", Integer.parseInt(xz[1]));
            block.addProperty("blockType", isPath ? blockType : shoulderBlockType);
            block.addProperty("role", isPath ? "path" : "shoulder");
            blocks.add(block);

            if (isPath) pathCount++; else shoulderCount++;
        }

        JsonObject response = new JsonObject();
        response.addProperty("total", blocks.size());
        response.addProperty("pathCount", pathCount);
        response.addProperty("shoulderCount", shoulderCount);
        response.add("blocks", blocks);

        logger.atInfo().log("[GENERATE_ROAD_CORRIDOR] Generated " + blocks.size() + " blocks ("
                + pathCount + " path, " + shoulderCount + " shoulder) across " + (waypoints.length - 1) + " segment(s)");

        return McpToolResponse.success(GSON.toJson(response));
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        // Pure computation, no world access - gate at the same level as scan_region (read-only tier)
        // rather than requiring a new permission flag/live config edit.
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
