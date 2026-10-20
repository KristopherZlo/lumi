package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.world.WorldStateApply;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** One shared cursor, a native executor, and a compact durable intent instead of Save/Restore. */
public final class AxiomReplayOperation implements DimensionMutation {
    private final LiveActionJournal.Plan plan;
    private final WorldStateApply.ApplySession before;
    private final WorldStateApply.ApplySession nativeReplay;
    private final RestorePublication origins;
    private final Executor background;
    private final Work prepareIntent, registerOrigins, publishCursor, clearIntent;
    private final LiveActionOperation.ReplayFactory portable, recovery;
    private Phase phase = Phase.PREFLIGHT;
    private CompletableFuture<Void> io;
    private boolean intentStarted, mutationStarted;
    private DimensionMutation delegate;
    private Throwable failure;

    public AxiomReplayOperation(LiveActionJournal.Plan plan, WorldStateApply.ApplySession before,
            WorldStateApply.ApplySession nativeReplay, RestorePublication origins, Executor background,
            Work prepareIntent, Work registerOrigins, Work publishCursor, Work clearIntent,
            LiveActionOperation.ReplayFactory portable, LiveActionOperation.ReplayFactory recovery) {
        this.plan = plan;
        this.before = before;
        this.nativeReplay = nativeReplay;
        this.origins = origins;
        this.background = background;
        this.prepareIntent = prepareIntent;
        this.registerOrigins = registerOrigins;
        this.publishCursor = publishCursor;
        this.clearIntent = clearIntent;
        this.portable = portable;
        this.recovery = recovery;
    }

    @Override public void advance(long deadline) throws IOException {
        if (delegate != null) { delegate.advance(deadline); return; }
        try {
            boolean first = true;
            while (!isTerminal() && (first || System.nanoTime() < deadline)) {
                first = false;
                switch (phase) {
                    case PREFLIGHT -> {
                        var result = before.verifyUntil(deadline);
                        if (result == WorldStateApply.Verification.IN_PROGRESS) return;
                        if (result == WorldStateApply.Verification.MISMATCH) { delegate = portable.prepare(plan); return; }
                        intentStarted = true;
                        io = async(prepareIntent);
                        phase = Phase.INTENT;
                    }
                    case INTENT -> {
                        if (!io.isDone()) return;
                        io.join();
                        registerOrigins.run();
                        phase = Phase.APPLY;
                    }
                    case APPLY -> {
                        mutationStarted = true;
                        if (!nativeReplay.applyUntil(deadline)) return;
                        phase = Phase.VERIFY;
                    }
                    case VERIFY -> {
                        var result = nativeReplay.verifyUntil(deadline);
                        if (result == WorldStateApply.Verification.IN_PROGRESS) return;
                        if (result == WorldStateApply.Verification.MISMATCH) throw new IOException("Native Axiom replay did not verify exactly");
                        phase = Phase.PERSIST;
                    }
                    case PERSIST -> {
                        if (!origins.awaitDurable(deadline) || !nativeReplay.persistUntil(deadline)) return;
                        io = async(publishCursor);
                        phase = Phase.PUBLISH;
                    }
                    case ABORT -> { if (!io.isDone()) return; io.join(); phase = Phase.FAILED; }
                    case PUBLISH -> { if (!io.isDone()) return; io.join(); phase = Phase.SUCCEEDED; }
                    case SUCCEEDED, FAILED, DEGRADED -> { return; }
                }
            }
        } catch (IOException | RuntimeException failed) {
            failure = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
            if (!intentStarted) { phase = Phase.FAILED; return; }
            if (phase == Phase.ABORT) { phase = Phase.DEGRADED; return; }
            if (!mutationStarted) { io = async(clearIntent); phase = Phase.ABORT; return; }
            LumiMod.LOGGER.warn("Native Axiom replay failed during {}; recovering under the same freeze", phase, failure);
            // Keep the same dimension freeze; only the exceptional path needs a full recovery apply.
            try { delegate = recovery.prepare(plan); }
            catch (IOException | RuntimeException repairFailed) { failure.addSuppressed(repairFailed); phase = Phase.DEGRADED; }
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
        if (!isTerminal()) throw new IllegalStateException("Native replay is not terminal");
        return phase == Phase.SUCCEEDED ? MutationTerminalState.SUCCEEDED : phase == Phase.FAILED ? MutationTerminalState.FAILED : MutationTerminalState.DEGRADED;
    }
    @Override public Optional<Throwable> failure() { return delegate != null ? delegate.failure() : Optional.ofNullable(failure); }
    @Override public OperationProgress progress() { return delegate != null ? delegate.progress() : OperationProgress.indeterminate("Axiom: " + phase.name().toLowerCase(java.util.Locale.ROOT)); }
    @Override public boolean cancel() { if (intentStarted || delegate != null) return false; phase = Phase.FAILED; return true; }
    @Override public void close() throws IOException { try { if (delegate != null) delegate.close(); } finally { before.close(); nativeReplay.close(); } }

    @FunctionalInterface public interface Work { void run() throws IOException; }
    private enum Phase { PREFLIGHT, INTENT, APPLY, VERIFY, PERSIST, PUBLISH, ABORT, SUCCEEDED, FAILED, DEGRADED }
}
