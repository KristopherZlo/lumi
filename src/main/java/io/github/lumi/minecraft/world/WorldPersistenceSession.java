package io.github.lumi.minecraft.world;

import java.io.IOException;

/** Advances one verified-world persistence barrier incrementally within tick deadlines. */
@FunctionalInterface
public interface WorldPersistenceSession extends AutoCloseable {
    WorldPersistenceSession COMPLETE = deadlineNanos -> true;

    boolean advanceUntil(long deadlineNanos) throws IOException;
    default String phase() { return "persistence"; }
    default Timings timings() { return Timings.EMPTY; }
    @Override
    default void close() { }

    /** Wall time spent writing, forcing and rereading one loaded Restore batch. */
    record Timings(long writeNanos, long syncNanos, long verificationNanos) {
        public static final Timings EMPTY = new Timings(0, 0, 0);

        public Timings {
            if (writeNanos < 0 || syncNanos < 0 || verificationNanos < 0) {
                throw new IllegalArgumentException("Persistence timings must be non-negative");
            }
        }
    }
}
