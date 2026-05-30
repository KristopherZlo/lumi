package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.RecoveryRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import net.minecraft.core.BlockPos;

final class PartialRestoreDraftRewriter {

    private final RecoveryRepository recoveryRepository = new RecoveryRepository();

    void preserveOutsideRestoredRegion(
            ProjectLayout layout,
            RecoveryDraft pendingDraft,
            Bounds3i bounds,
            PartialRestoreMode mode
    ) throws IOException {
        if (pendingDraft == null || pendingDraft.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            return;
        }
        PartialRestoreMode effectiveMode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
        List<StoredBlockChange> remaining = pendingDraft.changes().stream()
                .filter(change -> !effectiveMode.includes(bounds.contains(change.pos())))
                .toList();
        List<StoredEntityChange> remainingEntities = pendingDraft.entityChanges().stream()
                .filter(change -> !effectiveMode.includes(this.entityChangeInside(change, bounds)))
                .toList();
        if (remaining.isEmpty() && remainingEntities.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            return;
        }
        this.recoveryRepository.saveDraft(layout, new RecoveryDraft(
                pendingDraft.projectId(),
                pendingDraft.variantId(),
                pendingDraft.baseVersionId(),
                pendingDraft.actor(),
                pendingDraft.mutationSource(),
                pendingDraft.startedAt(),
                Instant.now(),
                remaining,
                remainingEntities
        ));
    }

    private boolean entityChangeInside(StoredEntityChange change, Bounds3i bounds) {
        if (change == null || bounds == null) {
            return false;
        }
        BlockPos pos = change.newValue() == null
                ? change.oldValue().blockPos()
                : change.newValue().blockPos();
        return bounds.contains(BlockPoint.from(pos));
    }
}
