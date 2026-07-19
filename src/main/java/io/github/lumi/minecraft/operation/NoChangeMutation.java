package io.github.lumi.minecraft.operation;

import java.util.Objects;
import java.util.Optional;

/** Successful frozen preflight result that performs no history or world work. */
public final class NoChangeMutation implements DimensionMutation {
    private final String completionMessage;

    public NoChangeMutation(String completionMessage) {
        this.completionMessage = Objects.requireNonNull(
                completionMessage, "completionMessage");
        if (completionMessage.isBlank()) {
            throw new IllegalArgumentException("Completion message cannot be blank");
        }
    }

    @Override public void advance(long deadlineNanos) { }
    @Override public boolean isTerminal() { return true; }
    @Override public boolean isSafeToRelease() { return true; }
    @Override public MutationTerminalState unhandledFailureState() {
        return MutationTerminalState.FAILED;
    }
    @Override public OperationProgress progress() {
        return OperationProgress.indeterminate("No changes");
    }
    @Override public Optional<String> completionMessage() {
        return Optional.of(completionMessage);
    }
}
