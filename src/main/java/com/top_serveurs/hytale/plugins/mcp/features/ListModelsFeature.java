package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
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
 * Lists registered Model asset ids (the "Appearance" value an NPC Role JSON references) so a
 * caller can pick a valid model without guessing. Pure asset-registry read, same risk profile as
 * BlockType.getAssetMap() elsewhere in this codebase - no world.execute() needed.
 */
public class ListModelsFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public ListModelsFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "list_models";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "list_models",
                "Lists registered Model asset ids that can be used as an NPC Role's Appearance value. "
                        + "Optionally filter by a case-insensitive substring of the id.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "search", McpToolSchema.stringProperty("Case-insensitive substring to filter model ids by (optional).")
            ),
            java.util.List.of()
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        try {
            String search = getArgumentAsString(call, "search");

            Map<String, ModelAsset> models = ModelAsset.getAssetMap().getAssetMap();

            List<String> ids = models.keySet().stream()
                    .filter(id -> search == null || search.isEmpty() || id.toLowerCase().contains(search.toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());

            JsonArray idsArray = new JsonArray();
            for (String id : ids) {
                idsArray.add(id);
            }

            JsonObject response = new JsonObject();
            response.addProperty("total", models.size());
            response.addProperty("returned", ids.size());
            response.add("models", idsArray);
            if (search != null && !search.isEmpty()) {
                response.addProperty("searchTerm", search);
            }

            logger.atInfo().log("[LIST_MODELS] Returned " + ids.size() + " of " + models.size() + " models"
                    + (search != null ? " (search: " + search + ")" : ""));

            return McpToolResponse.success(GSON.toJson(response));
        } catch (Throwable t) {
            logger.atSevere().withCause(t).log("[LIST_MODELS] Exception");
            return McpToolResponse.error("Failed to list models: " + t.getMessage());
        }
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canListModels();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canListModels();
        }
        return false;
    }

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }
}
