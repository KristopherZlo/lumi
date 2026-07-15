package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.operation.RestoreStateListener;
import java.util.Map;
import java.util.Objects;

/** Rebases runtime-only mutation baselines to Lumi's verified apply result. */
public final class RestoreBaselineReconciler implements RestoreStateListener {
    private final EntityChunkDurabilityGate entities;
    private final BlockEntityBaselineStore blockEntities;

    public RestoreBaselineReconciler(
            EntityChunkDurabilityGate entities,
            BlockEntityBaselineStore blockEntities) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.blockEntities = Objects.requireNonNull(blockEntities, "blockEntities");
    }

    @Override
    public void restored(PreparedRestore restore) {
        reconcile(restore.sections().keySet(), restore.entities());
    }

    @Override
    public void returned(PreparedRestore restore) {
        reconcile(restore.returnSections().keySet(), restore.returnEntities());
    }

    private void reconcile(
            Iterable<SectionKey> sections,
            Map<EntityChunkKey, EntityChunkBlob> entityChunks) {
        sections.forEach(blockEntities::discard);
        entityChunks.forEach(entities::rememberLoaded);
    }
}
