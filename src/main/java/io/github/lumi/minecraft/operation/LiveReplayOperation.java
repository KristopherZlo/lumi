package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.world.WorldStateApply;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** One shared cursor, the owning executor, and a compact durable intent instead of Save/Restore. */
public final class LiveReplayOperation implements DimensionMutation {
    private final LiveActionJournal.Plan plan;
    private final WorldStateApply.ApplySession before;
    private final WorldStateApply.ApplySession replay;
    private final RestorePublication origins;
    private final Executor background;
    private final Work prepareIntent, registerOrigins, releasePublication, publishCursor, clearIntent;
    private final LiveActionOperation.ReplayFactory portable, recovery;
    private Phase phase = Phase.PREFLIGHT;
    private CompletableFuture<Void> io;
    private boolean intentStarted, mutationStarted;
    private DimensionMutation delegate;
    private Throwable failure;
    private Work installCursor = () -> { };

    public LiveReplayOperation(LiveActionJournal.Plan plan, WorldStateApply.ApplySession before,
            WorldStateApply.ApplySession replay, RestorePublication origins, Executor background,
            Work prepareIntent, Work registerOrigins, Work releasePublication, Work publishCursor, Work clearIntent,
            LiveActionOperation.ReplayFactory portable, LiveActionOperation.ReplayFactory recovery) {
        this.plan = plan;
        this.before = before;
        this.replay = replay;
        this.origins = origins;
        this.background = background;
        this.prepareIntent = prepareIntent;
        this.registerOrigins = registerOrigins;
        this.releasePublication = releasePublication;
        this.publishCursor = publishCursor;
        this.clearIntent = clearIntent;
        this.portable = portable;
        this.recovery = recovery;
    }

    /** The owning editor already applied the world; only verification and durable publication remain. */
    public static LiveReplayOperation alreadyApplied(LiveActionJournal.Plan plan,
            WorldStateApply.ApplySession observed, RestorePublication origins, Executor background,
            Work registerOrigins, Work releasePublication, Work publishCursor, Work installCursor, LiveActionOperation.ReplayFactory recovery) {
        var operation = new LiveReplayOperation(plan, null, observed, origins, background,
                () -> { }, registerOrigins, releasePublication, publishCursor, () -> { }, recovery, recovery);
        operation.phase = Phase.OBSERVED;
        operation.io = CompletableFuture.completedFuture(null);
        operation.intentStarted = true;
        operation.mutationStarted = true;
        operation.installCursor = installCursor;
        return operation;
    }

    @Override public void advance(long deadline) throws IOException {
        if (delegate != null) { delegate.advance(deadline); return; }
        try {
            boolean first = true;
            while (!isTerminal() && (first || System.nanoTime() < deadline)) {
                first = false;
                switch (phase) {
                    case OBSERVED -> { registerOrigins.run(); phase = Phase.VERIFY; }
                    case PREFLIGHT -> {
                        var result = before.verifyUntil(deadline);
                        if (result == WorldStateApply.Verification.IN_PROGRESS) return;
                        if (result == WorldStateApply.Verification.MISMATCH) { delegate = portable.prepare(plan); return; }
                        intentStarted = true;
                        registerOrigins.run();
                        io = async(prepareIntent);
                        phase = Phase.APPLY;
                    }
                    case INTENT -> {
                        if (!io.isDone()) return;
                        io.join();
                        releasePublication.run();
                        phase = Phase.PERSIST;
                    }
                    case APPLY -> {
                        if (io.isCompletedExceptionally()) io.join();
                        mutationStarted = true;
                        if (!replay.applyUntil(deadline)) return;
                        phase = Phase.VERIFY;
                    }
                    case VERIFY -> {
                        var result = replay.verifyUntil(deadline);
                        if (result == WorldStateApply.Verification.IN_PROGRESS) return;
                        if (result == WorldStateApply.Verification.MISMATCH) throw new IOException("Live replay did not verify exactly");
                        phase = Phase.INTENT;
                    }
                    case PERSIST -> {
                        if (!origins.awaitDurable(deadline) || !replay.persistUntil(deadline)) return;
                        io = async(publishCursor);
                        phase = Phase.PUBLISH;
                    }
                    case ABORT -> { if (!io.isDone()) return; io.join(); releasePublication.run(); phase = Phase.FAILED; }
                    case RECOVER -> {
                        if (!io.isDone()) return;
                        io.join();
                        releasePublication.run();
                        delegate = recovery.prepare(plan);
                        return;
                    }
                    case PUBLISH -> { if (!io.isDone()) return; io.join(); installCursor.run(); phase = Phase.SUCCEEDED; }
                    case SUCCEEDED, FAILED, DEGRADED -> { return; }
                }
            }
        } catch (IOException | RuntimeException failed) {
            failure = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
            if (!intentStarted) { phase = Phase.FAILED; return; }
            if (phase == Phase.ABORT || phase == Phase.RECOVER) { phase = Phase.DEGRADED; return; }
            if (!mutationStarted) { io = async(clearIntent); phase = Phase.ABORT; return; }
            LumiMod.LOGGER.warn("Live replay failed during {}; recovering under the same freeze", phase, failure);
            // Do not publish speculative chunks, or start recovery, ahead of the pending intent.
            phase = Phase.RECOVER;
        }
    }

    private CompletableFuture<Void> async(Work work) {
        return CompletableFuture.runAsync(() -> {
            try { work.run(); } catch (IOException failed) { throw new CompletionException(failed); }
        }, background);
    }

    @Override public boolean isTerminal() { return delegate != null ? delegate.isTerminal() : phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.DEGRADED; }
    @Override public boolean isSafeToRelease() { return delegate != null ? delegate.isSafeToRelease() : phase == Phase.SUCCEEDED || phase == Phase.FAILED; }
    @Override public MutationTerminalState terminalState() {
        if (delegate != null) return delegate.terminalState();
        if (!isTerminal()) throw new IllegalStateException("Live replay is not terminal");
        return phase == Phase.SUCCEEDED ? MutationTerminalState.SUCCEEDED : phase == Phase.FAILED ? MutationTerminalState.FAILED : MutationTerminalState.DEGRADED;
    }
    @Override public Optional<Throwable> failure() { return delegate != null ? delegate.failure() : Optional.ofNullable(failure); }
    @Override public OperationProgress progress() { return delegate != null ? delegate.progress() : OperationProgress.indeterminate("Undo/Redo: " + phase.name().toLowerCase(java.util.Locale.ROOT)); }
    @Override public boolean cancel() { if (intentStarted || delegate != null) return false; phase = Phase.FAILED; return true; }
    @Override public void close() throws IOException { try { if (delegate != null) delegate.close(); } finally { if (before != null) before.close(); replay.close(); } }

    @FunctionalInterface public interface Work { void run() throws IOException; }
    private enum Phase { OBSERVED, PREFLIGHT, INTENT, APPLY, VERIFY, PERSIST, PUBLISH, ABORT, RECOVER, SUCCEEDED, FAILED, DEGRADED }
}
