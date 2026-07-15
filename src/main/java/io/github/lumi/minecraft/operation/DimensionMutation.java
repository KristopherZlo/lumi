package io.github.lumi.minecraft.operation;

import java.io.IOException;
import java.util.Optional;

/** A bounded, server-thread mutation owned by one dimension coordinator. */
public interface DimensionMutation {
    default boolean requiresFreeze() {
        return true;
    }

    void advance(long deadlineNanos) throws IOException;

    boolean isTerminal();

    boolean isSafeToRelease();

    default MutationTerminalState terminalState() {
        if (!isTerminal()) {
            throw new IllegalStateException("Mutation is not terminal");
        }
        return isSafeToRelease()
                ? MutationTerminalState.SUCCEEDED : MutationTerminalState.DEGRADED;
    }

    default Optional<Throwable> failure() {
        return Optional.empty();
    }
}
