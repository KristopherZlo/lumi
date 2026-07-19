package io.github.lumi.minecraft.operation;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Owns one off-thread dry-run or cleanup while the dimension queue is reserved. */
public final class GarbageCollectionOperation implements DimensionMutation {
    private final boolean applied;
    private final Callable<Counts> task;
    private final Executor background;
    private CompletableFuture<Counts> future;
    private Counts counts;
    private Throwable failure;

    public GarbageCollectionOperation(
            boolean applied, Callable<Counts> task, Executor background) {
        this.applied = applied;
        this.task = Objects.requireNonNull(task, "task");
        this.background = Objects.requireNonNull(background, "background");
    }

    @Override
    public boolean requiresFreeze() {
        return false;
    }

    @Override
    public void advance(long deadlineNanos) {
        if (future == null) {
            future = CompletableFuture.supplyAsync(this::run, background);
            return;
        }
        if (counts != null || failure != null || !future.isDone()) {
            return;
        }
        try {
            counts = future.join();
        } catch (CompletionException failed) {
            failure = failed.getCause() == null ? failed : failed.getCause();
        }
    }

    private Counts run() {
        try {
            return task.call();
        } catch (Exception failed) {
            throw new CompletionException(failed);
        }
    }

    public boolean applied() {
        return applied;
    }

    public Optional<Counts> counts() {
        return Optional.ofNullable(counts);
    }

    @Override
    public boolean isTerminal() {
        return counts != null || failure != null;
    }

    @Override
    public boolean isSafeToRelease() {
        return isTerminal();
    }

    @Override
    public MutationTerminalState terminalState() {
        if (!isTerminal()) {
            throw new IllegalStateException("Cleanup operation is not terminal");
        }
        return failure == null
                ? MutationTerminalState.SUCCEEDED : MutationTerminalState.FAILED;
    }

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public MutationTerminalState unhandledFailureState() {
        return MutationTerminalState.FAILED;
    }

    @Override
    public OperationProgress progress() {
        return OperationProgress.indeterminate(
                applied ? "Cleaning unused history" : "Inspecting unused history");
    }

    @Override
    public void close() throws IOException {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    public record Counts(int commits, int objects) {
        public Counts {
            if (commits < 0 || objects < 0) {
                throw new IllegalArgumentException("Cleanup counts cannot be negative");
            }
        }
    }
}
