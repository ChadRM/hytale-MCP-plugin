package com.top_serveurs.hytale.plugins.mcp.features;

import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;

import java.io.BufferedWriter;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared, single-slot state for the start_npc_trace/stop_npc_trace tool pair. Only one trace can
 * be active at a time - deliberately simple, matching the "flip it on for a debug session, flip it
 * off when done" use case rather than supporting concurrent traces. All mutation happens inside
 * World.execute() callbacks (either the periodic sample task or the start/stop tool calls
 * themselves), which serialize onto the world's own single thread, so this is effectively
 * single-threaded despite the fields not being individually synchronized.
 */
final class NpcTraceState {
    static volatile ScheduledExecutorService executor;
    static volatile ScheduledFuture<?> task;
    static volatile BufferedWriter writer;
    static volatile Vector3d lastKnownPosition;
    static volatile World world;
    static volatile String npcTypeIdFilter;
    static volatile double radius;
    static volatile long startTimeMillis;
    static volatile String filePath;
    static final AtomicInteger sampleCount = new AtomicInteger(0);
    static final AtomicInteger missCount = new AtomicInteger(0);

    static boolean isActive() {
        return writer != null;
    }

    private NpcTraceState() {
    }
}
