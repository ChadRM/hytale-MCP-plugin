package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lists real Item asset ids (weapons, tools, food, armor, etc.) - a separate registry from
 * BlockType (confirmed via the boot log's "Total Loaded Assets" line: Item ~3690 vs BlockType
 * ~5755). list_blocks/give_item only ever searched BlockType, so anything wieldable (a sword, a
 * held torch) was unfindable through this MCP before - found the real ids by extracting and
 * grepping vanilla Assets.zip directly (Server/Item/Items/**) instead, this tool closes that gap
 * properly. Item's own Categories (real data on the asset, e.g. "Weapon.Sword") are used directly
 * rather than guessing from the id string the way list_blocks's categorizeBlock() has to.
 */
public class ListItemsFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public ListItemsFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "list_items";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "list_items",
                "Lists real Item asset ids (weapons, tools, armor, food, etc.) - a separate registry "
                        + "from blocks (list_blocks only searches BlockType). Use this to find an item id "
                        + "for give_item or an NPC Role's inventory-equip Actions. Optionally filter by a "
                        + "case-insensitive substring of the id, and/or by an exact (case-insensitive) "
                        + "category string as it appears on the item (e.g. \"Weapon.Sword\") - call with no "
                        + "category to see what values exist for a given search first.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "search", McpToolSchema.stringProperty("Case-insensitive substring to filter item ids by (optional)."),
                "category", McpToolSchema.stringProperty("Exact (case-insensitive) category string to filter by, e.g. \"Weapon.Sword\" (optional)."),
                "limit", McpToolSchema.integerProperty("Maximum number of items to return (optional).")
            ),
            java.util.List.of()
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        try {
            String search = getArgumentAsString(call, "search");
            String category = getArgumentAsString(call, "category");
            Integer limit = getArgumentAsInteger(call, "limit");

            Map<String, Item> items = Item.getAssetMap().getAssetMap();

            List<String> ids = items.entrySet().stream()
                    .filter(e -> search == null || search.isEmpty()
                            || e.getKey().toLowerCase().contains(search.toLowerCase()))
                    .filter(e -> {
                        if (category == null || category.isEmpty()) {
                            return true;
                        }
                        String[] categories = e.getValue().getCategories();
                        if (categories == null) {
                            return false;
                        }
                        for (String c : categories) {
                            if (c.equalsIgnoreCase(category)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .map(Map.Entry::getKey)
                    .sorted()
                    .collect(Collectors.toList());

            int totalMatched = ids.size();
            if (limit != null && limit > 0 && limit < ids.size()) {
                ids = ids.subList(0, limit);
            }

            JsonArray idsArray = new JsonArray();
            for (String id : ids) {
                idsArray.add(id);
            }

            JsonObject response = new JsonObject();
            response.addProperty("total", items.size());
            response.addProperty("matched", totalMatched);
            response.addProperty("returned", ids.size());
            response.add("items", idsArray);
            if (search != null && !search.isEmpty()) {
                response.addProperty("searchTerm", search);
            }
            if (category != null && !category.isEmpty()) {
                response.addProperty("filterCategory", category);
            }

            logger.atInfo().log("[LIST_ITEMS] Returned " + ids.size() + " of " + totalMatched + " matched ("
                    + items.size() + " total)"
                    + (search != null ? " search=" + search : "")
                    + (category != null ? " category=" + category : ""));

            return McpToolResponse.success(GSON.toJson(response));
        } catch (Throwable t) {
            logger.atSevere().withCause(t).log("[LIST_ITEMS] Exception");
            return McpToolResponse.error("Failed to list items: " + t.getMessage());
        }
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canListItems();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canListItems();
        }
        return false;
    }

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getArgumentAsInteger(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
