package com.top_serveurs.hytale.plugins.mcp.features;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager;
import com.top_serveurs.hytale.plugins.mcp.auth.McpAuthManager.AuthLevel;
import com.top_serveurs.hytale.plugins.mcp.config.McpConfig;
import com.top_serveurs.hytale.plugins.mcp.models.McpTool;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolCall;
import com.top_serveurs.hytale.plugins.mcp.models.McpToolResponse;

import java.io.BufferedWriter;
import java.util.concurrent.CompletableFuture;

/**
 * Stops whatever trace start_npc_trace has running and closes its file so it stops growing.
 * Cancels the periodic schedule first (no new samples get submitted), then does one final
 * World.execute() pass to close the writer - since every sample write also goes through
 * World.execute() on the same world thread, that final pass is guaranteed to run after any sample
 * that was already in flight when stop was called, so nothing is lost or corrupted by the shutdown
 * race.
 */
public class StopNpcTraceFeature implements McpFeature {

    private static final Gson GSON = new Gson();
    private final HytaleLogger logger;

    public StopNpcTraceFeature(HytaleLogger logger, McpConfig config) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "stop_npc_trace";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
                "stop_npc_trace",
                "Stops the currently running NPC trace started by start_npc_trace and closes its file. "
                        + "No-op (returns tracing:false, wasRunning:false) if nothing is currently running.",
                "function"
        );
    }

    @Override
    public String getInputSchema() {
        return McpToolSchema.emptyObjectSchema();
    }

    @Override
    public McpToolResponse execute(McpToolCall call, AuthLevel authLevel) {
        if (!NpcTraceState.isActive()) {
            JsonObject response = new JsonObject();
            response.addProperty("tracing", false);
            response.addProperty("wasRunning", false);
            return McpToolResponse.success(GSON.toJson(response));
        }

        if (NpcTraceState.task != null) {
            NpcTraceState.task.cancel(false);
        }

        World world = NpcTraceState.world;
        CompletableFuture<McpToolResponse> future = new CompletableFuture<>();

        Runnable closeAndRespond = () -> {
            String filePath = NpcTraceState.filePath;
            int samples = NpcTraceState.sampleCount.get();
            int misses = NpcTraceState.missCount.get();

            BufferedWriter writer = NpcTraceState.writer;
            try {
                if (writer != null) {
                    writer.flush();
                    writer.close();
                }
            } catch (Exception e) {
                logger.atWarning().withCause(e).log("[STOP_NPC_TRACE] Failed to close trace file cleanly");
            }

            if (NpcTraceState.executor != null) {
                NpcTraceState.executor.shutdownNow();
            }

            NpcTraceState.writer = null;
            NpcTraceState.world = null;
            NpcTraceState.task = null;
            NpcTraceState.executor = null;
            NpcTraceState.lastKnownPosition = null;
            NpcTraceState.npcTypeIdFilter = null;
            NpcTraceState.filePath = null;

            JsonObject response = new JsonObject();
            response.addProperty("tracing", false);
            response.addProperty("wasRunning", true);
            response.addProperty("file", filePath);
            response.addProperty("sampleCount", samples);
            response.addProperty("missCount", misses);

            logger.atInfo().log("[STOP_NPC_TRACE] Stopped, " + samples + " samples (" + misses
                    + " misses) written to " + filePath);

            future.complete(McpToolResponse.success(GSON.toJson(response)));
        };

        if (world != null) {
            world.execute(closeAndRespond);
        } else {
            closeAndRespond.run();
        }

        return future.join();
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        if (authLevel == McpAuthManager.AuthLevel.ADMIN) {
            return config.getFeatures().getAdmins().canNpcTrace();
        }
        if (authLevel == McpAuthManager.AuthLevel.PLAYER) {
            return config.getFeatures().getPlayers().canNpcTrace();
        }
        return false;
    }
}
