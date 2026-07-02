package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ListPlayersFeature implements McpFeature {
    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public ListPlayersFeature(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "list_players";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
            "list_players",
            "Lists all currently connected players on the server",
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
            Map<String, World> worlds = Universe.get().getWorlds();
            if (worlds.isEmpty()) {
                return McpToolResponse.error("No world available to list players");
            }
            World world = worlds.values().iterator().next();

            // Universe/PlayerRef state must be read on the owning world's thread,
            // otherwise the MCP SDK's blocking sync tool call hangs forever with no
            // exception (the Jetty request thread is not the world thread).
            CompletableFuture<McpToolResponse> future = new CompletableFuture<>();

            world.execute(() -> {
                try {
                    Collection<PlayerRef> players = Universe.get().getPlayers();
                    JsonArray playerArray = new JsonArray();

                    for (PlayerRef player : players) {
                        JsonObject playerObj = new JsonObject();
                        playerObj.addProperty("uuid", player.getUuid().toString());
                        playerObj.addProperty("name", player.getUsername());
                        playerArray.add(playerObj);
                    }

                    JsonObject response = new JsonObject();
                    response.addProperty("count", players.size());
                    response.add("players", playerArray);

                    future.complete(McpToolResponse.success(GSON.toJson(response)));
                } catch (Throwable t) {
                    logger.atSevere().withCause(t).log("Error listing players");
                    future.complete(McpToolResponse.error("Failed to list players: " + t.getMessage()));
                }
            });

            return future.join();
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error listing players");
            return McpToolResponse.error("Failed to list players: " + e.getMessage());
        }
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canListPlayers();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canListPlayers();
        }
        return false;
    }
}
