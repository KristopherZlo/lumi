package io.github.lumi.minecraft.world;

import io.github.lumi.minecraft.operation.RestoreStateListener;
import java.io.IOException;
import java.util.Objects;

/** Rebases runtime-only mutation baselines to Lumi's verified apply result. */
public final class RestoreBaselineReconciler implements RestoreStateListener {
    @FunctionalInterface
    public interface StateReset {
        void reset(WorldStateApply.State state) throws IOException;
    }

    private final EntityChunkDurabilityGate entities;
    private final BlockEntityBaselineStore blockEntities;
    private final StateReset stateReset;

    public RestoreBaselineReconciler(
            EntityChunkDurabilityGate entities,
            BlockEntityBaselineStore blockEntities) {
        this(entities, blockEntities, ignored -> { });
    }

    public RestoreBaselineReconciler(
            EntityChunkDurabilityGate entities,
            BlockEntityBaselineStore blockEntities,
            StateReset stateReset) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.blockEntities = Objects.requireNonNull(blockEntities, "blockEntities");
        this.stateReset = Objects.requireNonNull(stateReset, "stateReset");
    }

    @Override
    public void restored(WorldStateApply.State state) throws IOException {
        reconcile(state);
    }

    @Override
    public void returned(WorldStateApply.State state) throws IOException {
        reconcile(state);
    }

    private void reconcile(WorldStateApply.State state) throws IOException {
        state.sections().keySet().forEach(blockEntities::discard);
        state.entities().forEach(entities::rebaseTracked);
        stateReset.reset(state);
    }
}
