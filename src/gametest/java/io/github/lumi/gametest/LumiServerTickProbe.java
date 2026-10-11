package io.github.lumi.gametest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/** Measures complete integrated-server ticks only while a benchmark lease is open. */
final class LumiServerTickProbe implements AutoCloseable {
    private static final Set<LumiServerTickProbe> ACTIVE =
            ConcurrentHashMap.newKeySet();

    static {
        var phase = Identifier.fromNamespaceAndPath("lumi", "benchmark_tick_probe");
        // Lumi advances operations on START too; begin the trace before its listener.
        ServerTickEvents.START_SERVER_TICK.addPhaseOrdering(
                phase, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        ServerTickEvents.START_SERVER_TICK.register(phase, server -> ACTIVE.forEach(
                probe -> probe.samplePreviousTick(server)));
        ServerTickEvents.END_SERVER_TICK.register(server -> ACTIVE.forEach(
                probe -> probe.endTrace(server)));
    }

    private final MinecraftServer server;
    private final int openedAtTick;
    private final AtomicLong maximumNanos = new AtomicLong();
    private final boolean tracing = Boolean.getBoolean("lumi.benchmark.trace");
    private TickTrace trace;

    private LumiServerTickProbe(MinecraftServer server) {
        this.server = server;
        openedAtTick = server.getTickCount();
        ACTIVE.add(this);
    }

    static LumiServerTickProbe open(MinecraftServer server) {
        return new LumiServerTickProbe(server);
    }

    long maximumNanos() {
        return maximumNanos.get();
    }

    private void samplePreviousTick(MinecraftServer tickingServer) {
        int completedTick = tickingServer.getTickCount() - 1;
        if (tickingServer == server && completedTick > openedAtTick) {
            long[] times = tickingServer.getTickTimesNanos();
            maximumNanos.accumulateAndGet(
                    times[Math.floorMod(completedTick, times.length)], Math::max);
        }
        if (tracing && tickingServer == server) {
            trace = new TickTrace();
            trace.tick = tickingServer.getTickCount();
            trace.begin();
        }
    }

    private void endTrace(MinecraftServer tickingServer) {
        if (tickingServer != server) return;
        TickTrace completed = trace;
        trace = null;
        if (completed == null) return;
        completed.end();
        completed.commit();
    }

    @Override public void close() {
        ACTIVE.remove(this);
    }

    @Name("lumi.ServerTick")
    @Label("Lumi benchmark server tick")
    @Category("Lumi")
    @StackTrace(false)
    static final class TickTrace extends Event {
        @Label("Server tick number")
        public int tick;
    }
}
