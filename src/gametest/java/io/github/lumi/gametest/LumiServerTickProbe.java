package io.github.lumi.gametest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/** Measures complete integrated-server ticks only while a benchmark lease is open. */
final class LumiServerTickProbe implements AutoCloseable {
    private static final Set<LumiServerTickProbe> ACTIVE =
            ConcurrentHashMap.newKeySet();

    static {
        ServerTickEvents.START_SERVER_TICK.register(server -> ACTIVE.forEach(
                probe -> probe.samplePreviousTick(server)));
    }

    private final MinecraftServer server;
    private final int openedAtTick;
    private final AtomicLong maximumNanos = new AtomicLong();

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
    }

    @Override public void close() {
        ACTIVE.remove(this);
    }
}
