package io.github.luma.integration.axiom;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks Axiom native undo/redo block-buffer replays so Lumi does not capture
 * its own delegated replay as a new builder action.
 */
public final class AxiomNativeUndoRedoGuard {

    private static final Duration EXPECTATION_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicInteger NEXT_TOKEN = new AtomicInteger(1);
    private static final Queue<ExpectedReplay> EXPECTED_REPLAYS = new ConcurrentLinkedQueue<>();

    private AxiomNativeUndoRedoGuard() {
    }

    public static int expectNativeReplay() {
        purgeExpired();
        int token = NEXT_TOKEN.getAndUpdate(previous -> previous == Integer.MAX_VALUE ? 1 : previous + 1);
        EXPECTED_REPLAYS.add(new ExpectedReplay(token, System.nanoTime() + EXPECTATION_TIMEOUT.toNanos()));
        return token;
    }

    public static boolean consumeExpectedNativeReplay() {
        purgeExpired();
        return EXPECTED_REPLAYS.poll() != null;
    }

    public static void cancelExpectedNativeReplay(int token) {
        purgeExpired();
        if (token <= 0) {
            return;
        }
        EXPECTED_REPLAYS.removeIf(replay -> replay.token() == token);
    }

    static int pendingNativeReplays() {
        purgeExpired();
        return EXPECTED_REPLAYS.size();
    }

    static void clearForTests() {
        EXPECTED_REPLAYS.clear();
        NEXT_TOKEN.set(1);
    }

    private static void purgeExpired() {
        long now = System.nanoTime();
        ExpectedReplay replay = EXPECTED_REPLAYS.peek();
        while (replay != null && replay.expired(now)) {
            EXPECTED_REPLAYS.poll();
            replay = EXPECTED_REPLAYS.peek();
        }
    }

    private record ExpectedReplay(int token, long expiresAtNanos) {

        private ExpectedReplay {
            if (token <= 0) {
                throw new IllegalArgumentException("token must be positive");
            }
        }

        private boolean expired(long now) {
            return now - this.expiresAtNanos >= 0;
        }
    }
}
