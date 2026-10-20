package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.CapturedWorldState;
import io.github.lumi.minecraft.world.DimensionFreeze;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import io.github.lumi.minecraft.world.WorldStateCapture;
import io.github.lumi.network.AxiomNativeReplayPayload;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Runs Axiom once, then publishes its observed result using the existing durable verifier. */
public final class AxiomNativeReplayOperation implements DimensionMutation {
    private final ServerPlayer actor;
    private final UUID request;
    private final DimensionFreezeState freeze;
    private final WorldStateCapture.CaptureSession capture;
    private final java.util.function.Supplier<WorldStateCapture.CaptureSession> recapture;
    private final Completion completion;
    private final DimensionFreeze.Lease publicationHold;
    private final java.util.function.Consumer<CapturedWorldState> captured;
    private final Runnable settle;
    private final net.minecraft.server.level.ServerLevel level;
    private final Set<UUID> dispatches = new HashSet<>();
    private CapturedWorldState before;
    private WorldStateCapture.CaptureSession after;
    private DimensionMutation delegate;
    private Throwable failure;
    private boolean sent, accepting, touched, released;
    private long deadline;

    public AxiomNativeReplayOperation(ServerPlayer actor, UUID request, DimensionFreezeState freeze,
            java.util.function.Supplier<WorldStateCapture.CaptureSession> capture,
            DimensionFreeze.Lease publicationHold, java.util.function.Consumer<CapturedWorldState> captured,
            Runnable settle, Completion completion) {
        this.actor = actor; this.request = request; this.freeze = freeze;
        this.level = actor.level(); this.captured = captured; this.settle = settle;
        this.recapture = capture; this.capture = capture.get();
        this.publicationHold = publicationHold; this.completion = completion;
    }

    public boolean dispatch(ServerPlayer player, UUID request, UUID dispatch, boolean complete) {
        if (player != actor || !this.request.equals(request)) return false;
        if (complete) dispatches.remove(dispatch);
        else if (accepting && !sent && dispatches.size() < 64) dispatches.add(dispatch);
        deadline = System.nanoTime() + 30_000_000_000L;
        return true;
    }

    /** Returns true for a native packet even when stale, so it cannot become a new Lumi edit. */
    public boolean run(ServerPlayer player, UUID dispatch, Runnable nativeApply) {
        if (player != actor || !dispatches.contains(dispatch)) return false;
        runRequest(player, request, nativeApply);
        return true;
    }

    public boolean runRequest(ServerPlayer player, UUID request, Runnable nativeApply) {
        if (player != actor || !this.request.equals(request)) return false;
        if (!accepting || sent || player.level() != level) return true;
        touched = true;
        try { freeze.runAuthorizedEntityAddition(nativeApply); }
        catch (RuntimeException | Error failed) { failure = failed; sent = true; accepting = false; }
        deadline = System.nanoTime() + 30_000_000_000L;
        return true;
    }

    public void accept(ServerPlayer player, AxiomNativeReplayPayload payload) {
        if (player != actor || !request.equals(payload.request()) || !accepting) return;
        if (payload.state() == AxiomNativeReplayPayload.State.READY) return;
        if (payload.state() == AxiomNativeReplayPayload.State.FAILED || !dispatches.isEmpty())
            failure = new IOException("Native Axiom replay did not finish its dispatches");
        sent = true; accepting = false;
    }

    @Override public void advance(long deadlineNanos) throws IOException {
        if (delegate != null) { delegate.advance(deadlineNanos); return; }
        if (before == null) {
            if (!capture.captureUntil(deadlineNanos)) return;
            before = capture.finish();
            captured.accept(before);
            accepting = true;
            deadline = System.nanoTime() + 30_000_000_000L;
            ServerPlayNetworking.send(actor, new AxiomNativeReplayPayload(request,
                    level.dimension().identifier().toString(), AxiomNativeReplayPayload.State.READY));
            return;
        }
        if (!sent && (actor.isRemoved() || actor.level() != level || level.getServer().getPlayerList().getPlayer(actor.getUUID()) != actor
                || System.nanoTime() >= deadline)) {
            failure = new IOException("Native Axiom client disconnected or timed out");
            sent = true; accepting = false;
        }
        if (!sent) return;
        if (after == null) {
            if (failure == null && !touched) failure = new IOException("Axiom sent no native history operation");
            if (failure == null) {
                try { freeze.runAuthorizedEntityAddition(settle); }
                catch (RuntimeException failed) { failure = failed; }
            }
            after = recapture.get();
        }
        if (!after.captureUntil(deadlineNanos)) return;
        delegate = completion.prepare(before, after.finish(), failure == null, this::releasePublication);
    }

    private void releasePublication() {
        if (!released) { publicationHold.release(); released = true; }
    }

    @Override public boolean isTerminal() { return delegate != null && delegate.isTerminal(); }
    @Override public boolean isSafeToRelease() { return delegate != null && delegate.isSafeToRelease(); }
    @Override public MutationTerminalState terminalState() {
        return failure != null && delegate.terminalState() == MutationTerminalState.SUCCEEDED
                ? MutationTerminalState.FAILED : delegate.terminalState();
    }
    @Override public Optional<Throwable> failure() { return failure != null ? Optional.of(failure) : delegate == null ? Optional.empty() : delegate.failure(); }
    @Override public OperationProgress progress() {
        return delegate == null ? OperationProgress.indeterminate("Axiom Undo/Redo") : delegate.progress();
    }
    @Override public void close() throws IOException {
        capture.close();
        if (after != null) after.close();
        if (delegate != null) delegate.close();
        // Unjournaled native writes must never escape through chunk storage on shutdown.
        if (!touched || isSafeToRelease()) releasePublication();
    }
    @FunctionalInterface public interface Completion {
        DimensionMutation prepare(CapturedWorldState before, CapturedWorldState after, boolean succeeded, Runnable journaled) throws IOException;
    }
}
