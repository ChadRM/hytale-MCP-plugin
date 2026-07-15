package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.List;

/**
 * Lists registered NPC role names (spawnable-only by default) so a caller can pick a valid
 * {@code role} value for spawn_npc without guessing. Pure asset-registry read, same risk profile
 * as BlockType.getAssetMap() elsewhere in this codebase - no world.execute() needed.
 */
public class ListNpcRolesFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public ListNpcRolesFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "list_npc_roles";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "list_npc_roles",
                "Lists registered NPC role names that can be passed to spawn_npc's role argument. "
                        + "Set includeNonSpawnable:true to also list abstract/template-only roles that "
                        + "can't be spawned directly.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "includeNonSpawnable", McpToolSchema.booleanProperty(
                        "If true, also include abstract/template roles that aren't directly spawnable. Defaults to false.")
            ),
            java.util.List.of()
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        try {
            boolean includeNonSpawnable = getArgumentAsBoolean(call, "includeNonSpawnable");

            List<String> roles = NPCPlugin.get().getRoleTemplateNames(!includeNonSpawnable);

            JsonArray rolesArray = new JsonArray();
            for (String role : roles) {
                rolesArray.add(role);
            }

            JsonObject response = new JsonObject();
            response.addProperty("count", roles.size());
            response.add("roles", rolesArray);

            logger.atInfo().log("[LIST_NPC_ROLES] Returned " + roles.size() + " roles");

            return McpToolResponse.success(GSON.toJson(response));
        } catch (Throwable t) {
            logger.atSevere().withCause(t).log("[LIST_NPC_ROLES] Exception");
            return McpToolResponse.error("Failed to list NPC roles: " + t.getMessage());
        }
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canListNpcRoles();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canListNpcRoles();
        }
        return false;
    }

    private boolean getArgumentAsBoolean(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }
}
