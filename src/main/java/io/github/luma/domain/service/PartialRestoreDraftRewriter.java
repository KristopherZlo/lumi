package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredChangeAccumulator;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.RecoveryRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

final class PartialRestoreDraftRewriter {

    private final RecoveryRepository recoveryRepository = new RecoveryRepository();

    RecoveryDraft mergeRestoredChanges(
            RecoveryDraft pendingDraft,
            RecoveryDraft restoredDraft,
            Instant updatedAt
    ) {
        if ((pendingDraft == null || pendingDraft.isEmpty()) && (restoredDraft == null || restoredDraft.isEmpty())) {
            return null;
        }

        StoredChangeAccumulator accumulator = new StoredChangeAccumulator();
        if (pendingDraft != null) {
            accumulator.addBlockChanges(pendingDraft.changes());
            accumulator.addEntityChanges(pendingDraft.entityChanges());
        }
        if (restoredDraft != null) {
            accumulator.addBlockChanges(restoredDraft.changes());
            accumulator.addEntityChanges(restoredDraft.entityChanges());
        }

        RecoveryDraft metadata = restoredDraft == null ? pendingDraft : restoredDraft;
        RecoveryDraft fallback = pendingDraft == null ? metadata : pendingDraft;
        RecoveryDraft merged = accumulator.toDraft(
                firstNonBlank(metadata.projectId(), fallback.projectId()),
                firstNonBlank(metadata.variantId(), fallback.variantId()),
                firstNonBlank(metadata.baseVersionId(), fallback.baseVersionId()),
                firstNonBlank(metadata.actor(), fallback.actor()),
                metadata.mutationSource() == null ? fallback.mutationSource() : metadata.mutationSource(),
                fallback.startedAt() == null ? metadata.startedAt() : fallback.startedAt(),
                updatedAt == null ? Instant.now() : updatedAt
        );
        return merged.isEmpty() ? null : merged;
    }

    RecoveryDraft emptyRestoredDraft(RecoveryDraft pendingDraft, RecoveryDraft restoredDraft, Instant updatedAt) {
        RecoveryDraft metadata = restoredDraft == null ? pendingDraft : restoredDraft;
        RecoveryDraft fallback = pendingDraft == null ? metadata : pendingDraft;
        Instant now = updatedAt == null ? Instant.now() : updatedAt;
        return new RecoveryDraft(
                firstNonBlank(metadata.projectId(), fallback.projectId()),
                firstNonBlank(metadata.variantId(), fallback.variantId()),
                firstNonBlank(metadata.baseVersionId(), fallback.baseVersionId()),
                firstNonBlank(metadata.actor(), fallback.actor()),
                metadata.mutationSource() == null ? fallback.mutationSource() : metadata.mutationSource(),
                fallback.startedAt() == null ? metadata.startedAt() : fallback.startedAt(),
                now,
                List.of(),
                List.of()
        );
    }

    void saveDraftOrDelete(ProjectLayout layout, RecoveryDraft draft) throws IOException {
        if (draft == null || draft.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            return;
        }
        this.recoveryRepository.saveDraft(layout, draft);
    }

    void preserveOutsideRestoredRegion(
            ProjectLayout layout,
            RecoveryDraft pendingDraft,
            Bounds3i bounds,
            PartialRestoreMode mode
    ) throws IOException {
        this.preserveOutsideRestoredRegion(layout, pendingDraft, bounds, mode, point -> true);
    }

    void preserveOutsideRestoredRegion(
            ProjectLayout layout,
            RecoveryDraft pendingDraft,
            Bounds3i bounds,
            PartialRestoreMode mode,
            Predicate<BlockPoint> hardScope
    ) throws IOException {
        if (pendingDraft == null || pendingDraft.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            return;
        }
        PartialRestoreMode effectiveMode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
        Predicate<BlockPoint> hardLimit = hardScope == null ? point -> true : hardScope;
        List<StoredBlockChange> remaining = pendingDraft.changes().stream()
                .filter(change -> !this.includes(change.pos(), bounds, effectiveMode, hardLimit))
                .toList();
        List<StoredEntityChange> remainingEntities = pendingDraft.entityChanges().stream()
                .filter(change -> !this.includes(this.entityPoint(change), bounds, effectiveMode, hardLimit))
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

    private boolean includes(
            BlockPoint point,
            Bounds3i bounds,
            PartialRestoreMode mode,
            Predicate<BlockPoint> hardScope
    ) {
        return point != null && hardScope.test(point) && mode.includes(bounds.contains(point));
    }

    private BlockPoint entityPoint(StoredEntityChange change) {
        if (change == null || (change.oldValue() == null && change.newValue() == null)) {
            return null;
        }
        BlockPos pos = change.newValue() == null
                ? change.oldValue().blockPos()
                : change.newValue().blockPos();
        return BlockPoint.from(pos);
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
