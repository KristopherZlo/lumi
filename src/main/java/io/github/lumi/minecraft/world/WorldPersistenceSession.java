package io.github.lumi.minecraft.world;

import java.io.IOException;

/** Advances one verified-world persistence barrier incrementally within tick deadlines. */
@FunctionalInterface
public interface WorldPersistenceSession extends AutoCloseable {
    WorldPersistenceSession COMPLETE = deadlineNanos -> true;
    boolean advanceUntil(long deadlineNanos) throws IOException;
    default String phase() { return "persistence"; }
    @Override
    default void close() { }
}
