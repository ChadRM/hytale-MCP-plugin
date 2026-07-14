package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.util.java.ManifestUtil;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

public class GetServerInfoFeature implements McpFeature {
    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;
    private final PluginIdentifier pluginId;

    public GetServerInfoFeature(HytaleLogger logger, McpConfig config, PluginIdentifier pluginId) {
        this.logger = logger;
        this.pluginId = pluginId;
    }

    @Override
    public String getName() {
        return "get_server_info";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
            "get_server_info",
            "Gets information about the server including name, version, and uptime",
            "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.emptyObjectSchema();
    }

    @Override
    public McpToolResponse execute(McpToolCall call, McpAuthManager.AuthLevel authLevel) {
        try {
            JsonObject response = new JsonObject();
            response.addProperty("name", pluginId.getName());
            response.addProperty("version", "1.0.0");
            response.addProperty("hytaleVersion", ManifestUtil.getImplementationVersion());
            response.addProperty("hytalePatchline", ManifestUtil.getPatchline());
            response.addProperty("hytaleRevision", ManifestUtil.getImplementationRevisionId());
            response.addProperty("uptime", getUptime());
            response.addProperty("tps", getTps());

            return McpToolResponse.success(GSON.toJson(response));
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error getting server info");
            return McpToolResponse.error("Failed to get server info: " + e.getMessage());
        }
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canGetServerInfo();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canGetServerInfo();
        }
        return false;
    }

    private String getUptime() {
        long uptimeMs = System.currentTimeMillis() - getStartTime();
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        return String.format("%d days, %d hours, %d minutes", days, hours % 24, minutes % 60);
    }

    private long getStartTime() {
        try {
            return HytaleServer.get().getBootStart();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private double getTps() {
        return 20.0;
    }
}
