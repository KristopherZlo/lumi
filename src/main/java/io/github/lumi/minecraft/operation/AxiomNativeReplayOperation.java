package io.github.lumi.minecraft.operation;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.domain.service.PermissionDecision;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import io.github.lumi.network.AxiomNativeReplayPayload;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Serializes the original editor's execution and cursor acknowledgement, without a Lumi Restore. */
public final class AxiomNativeReplayOperation implements DimensionMutation {
    private final ServerPlayer actor;
    private final ServerLevel level;
    private final UUID request;
    private final LiveActionJournal.Plan plan;
    private final DimensionFreezeState freeze;
    private final Runnable completion;
    private final Set<UUID> dispatches = new HashSet<>();
    private final Set<SectionKey> sections = new HashSet<>();
    private Throwable failure;
    private boolean admitted, sent, touched, terminal, applying;
    private long deadline;

    public AxiomNativeReplayOperation(ServerPlayer actor, UUID request, LiveActionJournal.Plan plan,
            DimensionFreezeState freeze, Runnable completion) {
        this.actor = actor; this.level = actor.level(); this.request = request;
        this.plan = plan; this.freeze = freeze; this.completion = completion;
    }

    public void dispatch(ServerPlayer player, UUID request, UUID dispatch, boolean complete) {
        if (player != actor || !this.request.equals(request) || terminal) return;
        if (complete) dispatches.remove(dispatch);
        else if (admitted && !sent) {
            if (dispatches.size() >= 64) fail(new IOException("Too many concurrent native Axiom dispatches"));
            else dispatches.add(dispatch);
        }
        deadline = System.nanoTime() + 30_000_000_000L;
    }

    /** Stale native packets are consumed so they cannot turn into new builder edits. */
    public boolean run(ServerPlayer player, UUID dispatch, Runnable nativeApply) {
        if (player != actor || !dispatches.contains(dispatch)) return false;
        runRequest(player, request, nativeApply);
        return true;
    }

    public void runRequest(ServerPlayer player, UUID request, Runnable nativeApply) {
        if (player != actor || !this.request.equals(request) || !admitted || sent || terminal || player.level() != level) return;
        applying = true;
        try {
            if (LumiMod.serverRuntime().permission(player) != PermissionDecision.ALLOWED)
                throw new IOException("Lumi editing permission changed");
            touched = true;
            freeze.runAuthorizedEntityAddition(nativeApply);
        } catch (IOException | RuntimeException failed) { fail(failed); }
        finally { applying = false; }
        deadline = System.nanoTime() + 30_000_000_000L;
    }

    public boolean applying() { return applying; }
    public LiveActionJournal.Plan plan() { return plan; }
    public boolean includeSection(SectionKey key) { return sections.add(key); }
    public Set<SectionKey> sectionKeys() { return Set.copyOf(sections); }

    public void accept(ServerPlayer player, AxiomNativeReplayPayload payload) {
        if (player != actor || !request.equals(payload.request()) || !admitted || sent || terminal) return;
        if (payload.state() == AxiomNativeReplayPayload.State.READY) return;
        if (payload.state() == AxiomNativeReplayPayload.State.FAILED || !dispatches.isEmpty())
            fail(new IOException("Native Axiom replay did not finish its dispatches"));
        sent = true;
    }

    private void fail(Throwable failed) { failure = failed; sent = true; }

    @Override public void advance(long deadlineNanos) {
        if (terminal) return;
        if (!admitted) {
            admitted = true;
            deadline = System.nanoTime() + 30_000_000_000L;
            ServerPlayNetworking.send(actor, new AxiomNativeReplayPayload(request,
                    level.dimension().identifier().toString(), AxiomNativeReplayPayload.State.READY));
            return;
        }
        if (!sent && (actor.isRemoved() || actor.level() != level || level.getServer().getPlayerList().getPlayer(actor.getUUID()) != actor
                || System.nanoTime() >= deadline)) fail(new IOException("Native Axiom client disconnected or timed out"));
        if (!sent) return;
        if (failure == null && !touched) fail(new IOException("Axiom sent no native history operation"));
        if (failure == null) {
            try { freeze.runAuthorizedEntityAddition(completion); }
            catch (RuntimeException failed) { fail(failed); }
        }
        // Native edits retain Axiom's ordinary persistence semantics; failed steps never move Lumi's cursor.
        terminal = true;
    }

    @Override public boolean isTerminal() { return terminal; }
    @Override public boolean isSafeToRelease() { return terminal; }
    @Override public MutationTerminalState terminalState() { return failure == null ? MutationTerminalState.SUCCEEDED : MutationTerminalState.FAILED; }
    @Override public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
    @Override public OperationProgress progress() { return OperationProgress.indeterminate("Axiom Undo/Redo"); }
}
