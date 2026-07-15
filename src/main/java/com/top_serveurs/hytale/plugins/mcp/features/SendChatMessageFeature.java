package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sends a chat message to one specific player - the previous implementation only logged the
 * message and returned {"sent": true} without ever calling into the game, so nothing actually
 * reached any player despite the tool description already claiming "sends a chat message to a
 * specific player." Real fix: PlayerRef.sendMessage(Message), the per-player counterpart to
 * BroadcastMessageFeature's Universe.get().sendMessage(Message.raw(...)).
 */
public class SendChatMessageFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public SendChatMessageFeature(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "send_chat_message";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "send_chat_message",
                "Sends a chat message to a specific player's in-game chat",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.schemaWithProperties(
            java.util.Map.of(
                "player", McpToolSchema.stringProperty("Player name or UUID to send the message to"),
                "message", McpToolSchema.stringProperty("Message to send in chat")
            ),
            java.util.List.of("player", "message")
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        String playerIdentifier = getArgumentAsString(call, "player");
        String message = getArgumentAsString(call, "message");

        if (playerIdentifier == null || playerIdentifier.isEmpty()) {
            return McpToolResponse.error("player is required");
        }
        if (message == null || message.isEmpty()) {
            return McpToolResponse.error("message is required");
        }

        Map<String, World> worlds = Universe.get().getWorlds();
        if (worlds.isEmpty()) {
            return McpToolResponse.error("No world available to send chat message");
        }
        World world = worlds.values().iterator().next();

        // PlayerRef state must be touched on the owning world's thread, same rule as every
        // other tool here that reaches into Universe/PlayerRef (see get_player_position) -
        // otherwise the call hangs forever with no exception on the Jetty request thread.
        CompletableFuture<McpToolResponse> future = new CompletableFuture<>();

        world.execute(() -> {
            try {
                PlayerRef player = findPlayer(playerIdentifier);
                if (player == null) {
                    future.complete(McpToolResponse.error("Player not found: " + playerIdentifier));
                    return;
                }

                player.sendMessage(Message.raw(message));

                JsonObject response = new JsonObject();
                response.addProperty("player", player.getUsername());
                response.addProperty("message", message);
                response.addProperty("sent", true);

                logger.atInfo().log("[SEND_CHAT_MESSAGE] To " + player.getUsername() + ": " + message);

                future.complete(McpToolResponse.success(GSON.toJson(response)));
            } catch (Throwable t) {
                logger.atSevere().withCause(t).log("[SEND_CHAT_MESSAGE] Exception");
                future.complete(McpToolResponse.error("Failed to send chat message: " + t.getMessage()));
            }
        });

        return future.join();
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
            return config.getFeatures().getAdmins().canSendChatMessage();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canSendChatMessage();
        }
        return false;
    }

    private String getArgumentAsString(McpToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value != null ? value.toString() : null;
    }
}
