package io.github.lumi.minecraft.operation;

import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import java.io.IOException;
import java.util.Optional;

/** A bounded, server-thread mutation owned by one dimension coordinator. */
public interface DimensionMutation extends AutoCloseable {
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

    /** Optional user-facing message for a successful terminal outcome. */
    default Optional<String> completionMessage() {
        return Optional.empty();
    }

    /** Exact apply measurements when this mutation completed a Restore endpoint. */
    default Optional<RestoreApplyStatistics> restoreStatistics() {
        return Optional.empty();
    }

    /**
     * Fail-closed state used when {@link #advance(long)} unexpectedly escapes.
     * Operations that cannot mutate the world may return {@link MutationTerminalState#FAILED}.
     */
    default MutationTerminalState unhandledFailureState() {
        return MutationTerminalState.DEGRADED;
    }

    default OperationProgress progress() {
        return OperationProgress.indeterminate(getClass().getSimpleName());
    }

    /** Cancels only while the operation can still prove that no unsafe mutation was exposed. */
    default boolean cancel() throws IOException {
        return false;
    }

    /** Releases resources when a queued operation is removed or its dimension closes. */
    @Override
    default void close() throws IOException { }
}
