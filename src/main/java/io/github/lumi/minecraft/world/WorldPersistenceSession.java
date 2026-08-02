package io.github.lumi.minecraft.world;

import java.io.IOException;
import java.util.List;

/** Advances one verified-world persistence stage or final barrier within tick deadlines. */
@FunctionalInterface
public interface WorldPersistenceSession extends AutoCloseable {
    WorldPersistenceSession COMPLETE = deadlineNanos -> true;

    boolean advanceUntil(long deadlineNanos) throws IOException;
    /** Chunks whose immutable write snapshots no longer require a live ticket. */
    default List<ChunkCoordinate> drainAcceptedSnapshotChunks() { return List.of(); }
    default String phase() { return "persistence"; }
    default Timings timings() { return Timings.EMPTY; }
    @Override
    default void close() { }

    /** Wall time spent writing, synchronizing and optionally rereading one Restore stage. */
    record Timings(
            long writeNanos,
            long lightingNanos,
            long syncNanos,
            long forceNanos,
            long verificationNanos) {
        public static final Timings EMPTY = new Timings(0, 0, 0, 0, 0);

        public Timings {
            if (writeNanos < 0 || lightingNanos < 0 || syncNanos < 0
                    || forceNanos < 0 || verificationNanos < 0) {
                throw new IllegalArgumentException("Persistence timings must be non-negative");
            }
        }
    }
}
