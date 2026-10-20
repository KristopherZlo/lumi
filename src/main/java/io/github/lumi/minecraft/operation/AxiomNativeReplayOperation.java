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
    private final io.github.lumi.domain.service.LiveActionJournal.Plan plan;
    private final java.util.Map<io.github.lumi.domain.model.HistoryKey, Long> keys;
    private final DimensionFreezeState freeze;
    private final WorldStateCapture.CaptureSession capture;
    private final java.util.function.Supplier<WorldStateCapture.CaptureSession> recapture;
    private final Completion completion;
    private final DimensionFreeze.Lease publicationHold;
    private final java.util.function.Consumer<CapturedWorldState> captured;
    private final Runnable settle;
    private final net.minecraft.server.level.ServerLevel level;
    private final Set<UUID> dispatches = new HashSet<>();
    private final java.util.List<DimensionFreeze.Lease> extraHolds = new java.util.ArrayList<>();
    private final Set<io.github.lumi.domain.model.BiomePosition> biomes;
    private CapturedWorldState before;
    private WorldStateCapture.CaptureSession after;
    private DimensionMutation delegate;
    private Throwable failure;
    private boolean sent, accepting, touched, released;
    private boolean applying;
    private long deadline;

    public AxiomNativeReplayOperation(ServerPlayer actor, UUID request, io.github.lumi.domain.service.LiveActionJournal.Plan plan,
            java.util.Map<io.github.lumi.domain.model.HistoryKey, Long> keys, DimensionFreezeState freeze,
            java.util.function.Supplier<WorldStateCapture.CaptureSession> capture,
            DimensionFreeze.Lease publicationHold, java.util.function.Consumer<CapturedWorldState> captured,
            Runnable settle, Completion completion) {
        this.actor = actor; this.request = request; this.freeze = freeze;
        this.plan = plan; this.keys = keys;
        this.biomes = new HashSet<>(plan.biomes().keySet());
        this.level = actor.level(); this.captured = captured; this.settle = settle;
        this.recapture = capture; this.capture = capture.get();
        this.publicationHold = publicationHold; this.completion = completion;
    }

    public boolean dispatch(ServerPlayer player, UUID request, UUID dispatch, boolean complete) {
        if (player != actor || !this.request.equals(request)) return false;
        if (complete) dispatches.remove(dispatch);
        else if (accepting && !sent) {
            if (dispatches.size() >= 64) {
                failure = new IOException("Too many concurrent native Axiom dispatches");
                sent = true; accepting = false;
            } else dispatches.add(dispatch);
        }
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
        applying = true;
        try {
            if (io.github.lumi.LumiMod.serverRuntime().permission(player) != io.github.lumi.domain.service.PermissionDecision.ALLOWED)
                throw new IllegalStateException("Lumi editing permission changed");
            freeze.runAuthorizedEntityAddition(nativeApply);
        }
        catch (IOException | RuntimeException | Error failed) { failure = failed; sent = true; accepting = false; }
        finally { applying = false; }
        deadline = System.nanoTime() + 30_000_000_000L;
        return true;
    }

    public boolean applying() { return applying; }
    public io.github.lumi.domain.service.LiveActionJournal.Plan plan() { return plan; }
    public boolean includes(io.github.lumi.domain.model.SectionKey key) { return before.sections().containsKey(key); }
    public io.github.lumi.domain.model.SectionBlob source(io.github.lumi.domain.model.SectionKey key) { return before.sections().get(key); }
    public Set<io.github.lumi.domain.model.BiomePosition> biomes() { return biomes; }
    public void include(io.github.lumi.domain.model.SectionKey key, io.github.lumi.domain.model.SectionBlob section, DimensionFreeze.Lease hold) {
        if (hold != null) extraHolds.add(hold);
        if (!includes(key) && before.sections().size() >= 4096) throw new IllegalStateException("Native replay exceeded its section capture limit");
        keys.put(key, 1L);
        var sections = new java.util.HashMap<>(before.sections()); sections.put(key, section);
        before = new CapturedWorldState(sections, before.entities(), new io.github.lumi.domain.model.WorkingIndexSnapshot(keys),
                new io.github.lumi.domain.model.CommitStatistics(sections.size(), before.entities().size(),
                        (long) sections.size() * io.github.lumi.domain.model.SectionBlob.BLOCK_COUNT, before.statistics().entities()));
    }

    public Set<io.github.lumi.domain.model.SectionKey> sectionKeys() { return before == null ? Set.of() : before.sections().keySet(); }

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
        if (!released) { publicationHold.release(); extraHolds.forEach(DimensionFreeze.Lease::release); released = true; }
    }

    @Override public boolean isTerminal() { return delegate != null && delegate.isTerminal(); }
    @Override public boolean isSafeToRelease() { return delegate != null && delegate.isSafeToRelease(); }
    @Override public MutationTerminalState terminalState() {
        return failure != null && delegate.terminalState() == MutationTerminalState.SUCCEEDED
                ? MutationTerminalState.FAILED : delegate.terminalState();
    }
    @Override public Optional<Throwable> failure() { return failure != null ? Optional.of(failure) : delegate == null ? Optional.empty() : delegate.failure(); }
    @Override public OperationProgress progress() {
        var progress = delegate == null ? null : delegate.progress();
        return progress == null ? OperationProgress.indeterminate("Axiom Undo/Redo")
                : new OperationProgress("Axiom Undo/Redo", progress.completed(), progress.total());
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
