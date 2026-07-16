package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.minecraft.operation.OperationProgress;
import java.io.IOException;

/** Discovers pending visible state and establishes its durable capture boundary. */
public interface SavePreparation {
    Session begin();

    interface Session extends AutoCloseable {
        boolean prepareUntil(long deadlineNanos) throws IOException;

        WorkingIndexSnapshot finish();

        default OperationProgress progress() {
            return OperationProgress.indeterminate("Save: preparing visible state");
        }

        @Override
        default void close() throws IOException { }
    }
}
