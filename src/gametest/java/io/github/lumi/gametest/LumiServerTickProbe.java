package io.github.lumi.gametest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Measures complete integrated-server ticks only while a benchmark lease is open. */
final class LumiServerTickProbe implements AutoCloseable {
    private static final Set<LumiServerTickProbe> ACTIVE =
            ConcurrentHashMap.newKeySet();

    static {
        ServerTickEvents.START_SERVER_TICK.register(server ->
                ACTIVE.forEach(LumiServerTickProbe::startTick));
        ServerTickEvents.END_SERVER_TICK.register(server ->
                ACTIVE.forEach(LumiServerTickProbe::endTick));
    }

    private final AtomicLong tickStarted = new AtomicLong();
    private final AtomicLong maximumNanos = new AtomicLong();

    private LumiServerTickProbe() {
        ACTIVE.add(this);
    }

    static LumiServerTickProbe open() {
        return new LumiServerTickProbe();
    }

    long maximumNanos() {
        return maximumNanos.get();
    }

    private void startTick() {
        tickStarted.set(System.nanoTime());
    }

    private void endTick() {
        long started = tickStarted.getAndSet(0);
        if (started != 0) {
            maximumNanos.accumulateAndGet(
                    System.nanoTime() - started, Math::max);
        }
    }

    @Override public void close() {
        ACTIVE.remove(this);
    }
}
