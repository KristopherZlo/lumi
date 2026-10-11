package io.github.lumi.gametest;

import java.util.concurrent.atomic.AtomicLong;

/** Test-only lease measuring server handlers for one real client action. */
public final class LumiInteractionTiming implements AutoCloseable {
    private static volatile LumiInteractionTiming active;
    private final AtomicLong handlerNanos = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong clientAttempts = new AtomicLong();
    private final AtomicLong clientUses = new AtomicLong();
    private final AtomicLong serverPackets = new AtomicLong();
    private volatile long handlerStarted;
    private volatile long handlerFinished;

    private LumiInteractionTiming() { }

    static LumiInteractionTiming open() {
        if (active != null) throw new IllegalStateException("Interaction probe is already active");
        var timing = new LumiInteractionTiming();
        active = timing;
        return timing;
    }

    public static void record(long started) {
        var timing = active;
        if (timing != null) {
            long finished = System.nanoTime();
            timing.handlerStarted = started;
            timing.handlerFinished = finished;
            timing.handlerNanos.addAndGet(finished - started);
            timing.calls.incrementAndGet();
        }
    }

    long handlerNanos() { return handlerNanos.get(); }
    long calls() { return calls.get(); }
    long clientAttempts() { return clientAttempts.get(); }
    long clientUses() { return clientUses.get(); }
    long serverPackets() { return serverPackets.get(); }

    public static void recordClientAttempt() {
        var timing = active;
        if (timing != null) timing.clientAttempts.incrementAndGet();
    }

    public static void recordClientUse() {
        var timing = active;
        if (timing != null) timing.clientUses.incrementAndGet();
    }

    public static void recordServerPacket() {
        var timing = active;
        if (timing != null) timing.serverPackets.incrementAndGet();
    }
    long handlerStarted() { return handlerStarted; }
    long handlerFinished() { return handlerFinished; }

    @Override public void close() { active = null; }
}
