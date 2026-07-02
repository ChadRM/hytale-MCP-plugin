package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class GetPlayerPositionFeature implements McpFeature {
    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public GetPlayerPositionFeature(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "get_player_position";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
            "get_player_position",
            "Gets the current position (x, y, z) of a specific player",
            "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "player", McpToolSchema.stringProperty("Player name or UUID")
            ),
            java.util.List.of("player")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, McpAuthManager.AuthLevel authLevel) {
        try {
            Map<String, Object> args = call.getArguments();
            if (!args.containsKey("player")) {
                return McpToolResponse.error("Missing required parameter: player");
            }

            String playerIdentifier = args.get("player").toString();

            Map<String, World> worlds = Universe.get().getWorlds();
            if (worlds.isEmpty()) {
                return McpToolResponse.error("No world available to get player position");
            }
            World world = worlds.values().iterator().next();

            // Universe/PlayerRef state (including live transform) must be read on the
            // owning world's thread, otherwise the MCP SDK's blocking sync tool call
            // hangs forever with no exception (the Jetty request thread is not the
            // world thread).
            CompletableFuture<McpToolResponse> future = new CompletableFuture<>();

            world.execute(() -> {
                try {
                    PlayerRef player = findPlayer(playerIdentifier);

                    if (player == null) {
                        future.complete(McpToolResponse.error("Player not found: " + playerIdentifier));
                        return;
                    }

                    com.hypixel.hytale.math.vector.Transform transform = player.getTransform();
                    org.joml.Vector3d pos = transform.getPosition();
                    com.hypixel.hytale.math.vector.Rotation3f rotation = transform.getRotation();

                    JsonObject position = new JsonObject();
                    position.addProperty("x", pos.x());
                    position.addProperty("y", pos.y());
                    position.addProperty("z", pos.z());
                    position.addProperty("worldUuid", player.getWorldUuid().toString());
                    position.addProperty("yaw", rotation.yaw());
                    position.addProperty("pitch", rotation.pitch());

                    JsonObject response = new JsonObject();
                    response.addProperty("name", player.getUsername());
                    response.addProperty("uuid", player.getUuid().toString());
                    response.add("position", position);

                    future.complete(McpToolResponse.success(GSON.toJson(response)));
                } catch (Throwable t) {
                    logger.atSevere().withCause(t).log("Error getting player position");
                    future.complete(McpToolResponse.error("Failed to get player position: " + t.getMessage()));
                }
            });

            return future.join();
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error getting player position");
            return McpToolResponse.error("Failed to get player position: " + e.getMessage());
        }
    }

    private PlayerRef findPlayer(String identifier) {
        Collection<PlayerRef> players = Universe.get().getPlayers();
        
        try {
            UUID uuid = UUID.fromString(identifier);
            for (PlayerRef player : players) {
                if (player.getUuid().equals(uuid)) {
                    return player;
                }
            }
        } catch (IllegalArgumentException e) {
        }
        
        for (PlayerRef player : players) {
            if (player.getUsername().equalsIgnoreCase(identifier)) {
                return player;
            }
        }
        
        return null;
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canGetPlayerPosition();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canGetPlayerPosition();
        }
        return false;
    }
}
