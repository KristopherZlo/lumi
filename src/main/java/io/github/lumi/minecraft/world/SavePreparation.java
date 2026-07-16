package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.io.IOException;

/** Discovers pending visible state and establishes its durable capture boundary. */
public interface SavePreparation {
    Session begin();

    interface Session extends AutoCloseable {
        boolean prepareUntil(long deadlineNanos) throws IOException;

        WorkingIndexSnapshot finish();

        @Override
        default void close() throws IOException { }
    }
}
