package io.github.lumi.minecraft.world;

import io.github.lumi.minecraft.operation.RestoreStateListener;
import java.util.Objects;
import java.util.function.Consumer;

/** Rebases runtime-only mutation baselines to Lumi's verified apply result. */
public final class RestoreBaselineReconciler implements RestoreStateListener {
    private final EntityChunkDurabilityGate entities;
    private final BlockEntityBaselineStore blockEntities;
    private final Consumer<WorldStateApply.State> stateReset;

    public RestoreBaselineReconciler(
            EntityChunkDurabilityGate entities,
            BlockEntityBaselineStore blockEntities) {
        this(entities, blockEntities, ignored -> { });
    }

    public RestoreBaselineReconciler(
            EntityChunkDurabilityGate entities,
            BlockEntityBaselineStore blockEntities,
            Consumer<WorldStateApply.State> stateReset) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.blockEntities = Objects.requireNonNull(blockEntities, "blockEntities");
        this.stateReset = Objects.requireNonNull(stateReset, "stateReset");
    }

    @Override
    public void restored(WorldStateApply.State state) {
        reconcile(state);
    }

    @Override
    public void returned(WorldStateApply.State state) {
        reconcile(state);
    }

    private void reconcile(WorldStateApply.State state) {
        state.sections().keySet().forEach(blockEntities::discard);
        state.entities().forEach(entities::rebaseTracked);
        stateReset.accept(state);
    }
}
