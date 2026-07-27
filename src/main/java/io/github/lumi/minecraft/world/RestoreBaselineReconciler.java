package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.operation.RestoreStateListener;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Rebases runtime-only mutation baselines to Lumi's verified apply result. */
public final class RestoreBaselineReconciler implements RestoreStateListener {
    private final EntityChunkDurabilityGate entities;
    private final BlockEntityBaselineStore blockEntities;
    private final Consumer<Set<SectionKey>> sectionReset;

    public RestoreBaselineReconciler(
            EntityChunkDurabilityGate entities,
            BlockEntityBaselineStore blockEntities) {
        this(entities, blockEntities, ignored -> { });
    }

    public RestoreBaselineReconciler(
            EntityChunkDurabilityGate entities,
            BlockEntityBaselineStore blockEntities,
            Consumer<Set<SectionKey>> sectionReset) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.blockEntities = Objects.requireNonNull(blockEntities, "blockEntities");
        this.sectionReset = Objects.requireNonNull(sectionReset, "sectionReset");
    }

    @Override
    public void restored(WorldStateApply.State state) {
        reconcile(state.sections().keySet(), state.entities());
    }

    @Override
    public void returned(WorldStateApply.State state) {
        reconcile(state.sections().keySet(), state.entities());
    }

    private void reconcile(
            Set<SectionKey> sections,
            Map<EntityChunkKey, EntityChunkBlob> entityChunks) {
        sections.forEach(blockEntities::discard);
        entityChunks.forEach(entities::rebaseTracked);
        sectionReset.accept(Set.copyOf(sections));
    }
}
