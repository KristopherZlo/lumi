package io.github.lumi.minecraft.operation;

import io.github.lumi.storage.object.WorldActionIndexCodec;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Holds the dimension until a metadata-only history clear is durable and installed. */
public final class WorldActionClearOperation implements DimensionMutation {
    private final CompletableFuture<Runnable> publication;
    private volatile boolean published;
    private boolean done;
    private Throwable failure;

    public WorldActionClearOperation(Callable<WorldActionIndexCodec.State> task,
            Function<WorldActionIndexCodec.State, Runnable> installation, Executor background) {
        publication = CompletableFuture.supplyAsync(() -> {
            try {
                var state = task.call();
                published = true;
                return installation.apply(state);
            } catch (Exception failed) { throw new CompletionException(failed); }
        }, background);
    }

    @Override public void advance(long deadlineNanos) {
        if (done || !publication.isDone()) return;
        try {
            publication.join().run();
        } catch (RuntimeException failed) {
            failure = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
        }
        done = true;
    }
    @Override public boolean isTerminal() { return done; }
    @Override public boolean isSafeToRelease() { return done && (failure == null || !published); }
    @Override public MutationTerminalState terminalState() {
        if (!done) throw new IllegalStateException("History clear is not terminal");
        return failure == null ? MutationTerminalState.SUCCEEDED : published ? MutationTerminalState.DEGRADED : MutationTerminalState.FAILED;
    }
    @Override public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
    @Override public OperationProgress progress() { return OperationProgress.indeterminate("Clearing Undo/Redo"); }
    @Override public MutationTerminalState unhandledFailureState() { return MutationTerminalState.DEGRADED; }
}
