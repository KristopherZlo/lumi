package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * Immutable action plan for rolling pending draft changes back to their saved
 * base state while keeping the rollback undoable through the live undo stack.
 */
final class QuickRollbackDraftPlan {

    private static final String ACTOR = "Lumi quick rollback";

    private final String actionId;
    private final List<StoredBlockChange> blockChanges;
    private final List<StoredEntityChange> entityChanges;
    private final RecoveryDraft remainingDraft;

    private QuickRollbackDraftPlan(
            String actionId,
            List<StoredBlockChange> blockChanges,
            List<StoredEntityChange> entityChanges,
            RecoveryDraft remainingDraft
    ) {
        this.actionId = actionId;
        this.blockChanges = blockChanges == null ? List.of() : List.copyOf(blockChanges);
        this.entityChanges = entityChanges == null ? List.of() : List.copyOf(entityChanges);
        this.remainingDraft = remainingDraft == null || remainingDraft.isEmpty() ? null : remainingDraft;
    }

    static QuickRollbackDraftPlan fromDraft(String targetVersionId, RecoveryDraft draft) {
        return fromDraft(targetVersionId, draft, null);
    }

    static QuickRollbackDraftPlan fromDraft(String targetVersionId, RecoveryDraft draft, Bounds3i bounds) {
        if (draft == null || draft.isEmpty()) {
            return empty(targetVersionId);
        }
        if (bounds != null) {
            return selectedArea(targetVersionId, draft, bounds);
        }
        return new QuickRollbackDraftPlan(
                "quick-rollback-" + normalizedTargetVersionId(targetVersionId) + "-" + UUID.randomUUID(),
                draft.changes().stream()
                        .map(QuickRollbackDraftPlan::inverse)
                        .toList(),
                draft.entityChanges().stream()
                        .map(StoredEntityChange::inverse)
                        .toList(),
                null
        );
    }

    private static QuickRollbackDraftPlan empty(String targetVersionId) {
        return new QuickRollbackDraftPlan(
                "quick-rollback-" + normalizedTargetVersionId(targetVersionId) + "-" + UUID.randomUUID(),
                List.of(),
                List.of(),
                null
        );
    }

    private static QuickRollbackDraftPlan selectedArea(String targetVersionId, RecoveryDraft draft, Bounds3i bounds) {
        List<StoredBlockChange> selectedBlocks = new ArrayList<>();
        List<StoredBlockChange> remainingBlocks = new ArrayList<>();
        for (StoredBlockChange change : draft.changes()) {
            if (bounds.contains(change.pos())) {
                selectedBlocks.add(inverse(change));
            } else {
                remainingBlocks.add(change);
            }
        }

        List<StoredEntityChange> selectedEntities = new ArrayList<>();
        List<StoredEntityChange> remainingEntities = new ArrayList<>();
        for (StoredEntityChange change : draft.entityChanges()) {
            if (entityInside(change, bounds)) {
                selectedEntities.add(change.inverse());
            } else {
                remainingEntities.add(change);
            }
        }

        return new QuickRollbackDraftPlan(
                "quick-rollback-" + normalizedTargetVersionId(targetVersionId) + "-" + UUID.randomUUID(),
                selectedBlocks,
                selectedEntities,
                remainingDraft(draft, remainingBlocks, remainingEntities)
        );
    }

    private static StoredBlockChange inverse(StoredBlockChange change) {
        return change.inverse();
    }

    private static RecoveryDraft remainingDraft(
            RecoveryDraft draft,
            List<StoredBlockChange> blockChanges,
            List<StoredEntityChange> entityChanges
    ) {
        if ((blockChanges == null || blockChanges.isEmpty()) && (entityChanges == null || entityChanges.isEmpty())) {
            return null;
        }
        return new RecoveryDraft(
                draft.projectId(),
                draft.variantId(),
                draft.baseVersionId(),
                draft.actor(),
                draft.mutationSource(),
                draft.startedAt(),
                Instant.now(),
                blockChanges,
                entityChanges
        );
    }

    private static boolean entityInside(StoredEntityChange change, Bounds3i bounds) {
        if (change == null || bounds == null) {
            return false;
        }
        BlockPos pos = change.newValue() == null
                ? change.oldValue() == null ? BlockPos.ZERO : change.oldValue().blockPos()
                : change.newValue().blockPos();
        return bounds.contains(BlockPoint.from(pos));
    }

    private static String normalizedTargetVersionId(String targetVersionId) {
        return targetVersionId == null || targetVersionId.isBlank() ? "head" : targetVersionId;
    }

    String actionId() {
        return this.actionId;
    }

    String actor() {
        return ACTOR;
    }

    List<StoredBlockChange> blockChanges() {
        return this.blockChanges;
    }

    List<StoredEntityChange> entityChanges() {
        return this.entityChanges;
    }

    RecoveryDraft remainingDraft() {
        return this.remainingDraft;
    }

    int totalChangeCount() {
        return this.blockChanges.size() + this.entityChanges.size();
    }

    boolean isEmpty() {
        return this.blockChanges.isEmpty() && this.entityChanges.isEmpty();
    }
}
