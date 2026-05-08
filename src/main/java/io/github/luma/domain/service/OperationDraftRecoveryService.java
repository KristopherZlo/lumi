package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredChangeAccumulator;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.RecoveryRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Promotes save/amend operation drafts that survived an interrupted operation.
 */
public final class OperationDraftRecoveryService {

    private final RecoveryRepository recoveryRepository;

    public OperationDraftRecoveryService() {
        this(new RecoveryRepository());
    }

    OperationDraftRecoveryService(RecoveryRepository recoveryRepository) {
        this.recoveryRepository = Objects.requireNonNull(recoveryRepository, "recoveryRepository");
    }

    public Optional<RecoveryDraft> restoreInterruptedOperationDraft(
            ProjectLayout layout,
            BuildProject project
    ) throws IOException {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(project, "project");

        Optional<RecoveryDraft> operationDraft = this.recoveryRepository.loadOperationDraft(layout);
        if (operationDraft.isEmpty()) {
            return Optional.empty();
        }
        RecoveryDraft pending = operationDraft.get();
        if (!project.id().toString().equals(pending.projectId())) {
            LumaMod.LOGGER.warn(
                    "Keeping operation draft for project {} hidden because it belongs to project id {}",
                    project.name(),
                    pending.projectId()
            );
            return Optional.empty();
        }

        Optional<RecoveryDraft> liveDraft = this.recoveryRepository.loadDraft(layout);
        Optional<RecoveryDraft> restored = liveDraft
                .map(live -> this.mergeCompatibleDrafts(project, live, pending))
                .orElse(Optional.of(pending));
        if (restored.isEmpty()) {
            return Optional.empty();
        }

        RecoveryDraft draft = restored.get();
        if (draft.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            this.recoveryRepository.deleteOperationDraft(layout);
            return Optional.empty();
        }

        this.recoveryRepository.saveDraft(layout, draft);
        this.recoveryRepository.deleteOperationDraft(layout);
        LumaMod.LOGGER.warn(
                "Restored interrupted operation draft for project {} with {} pending changes",
                project.name(),
                draft.totalChangeCount()
        );
        return Optional.of(draft);
    }

    private Optional<RecoveryDraft> mergeCompatibleDrafts(
            BuildProject project,
            RecoveryDraft liveDraft,
            RecoveryDraft operationDraft
    ) {
        if (!compatible(liveDraft, operationDraft)) {
            LumaMod.LOGGER.warn(
                    "Keeping operation draft for project {} hidden because its base {}:{} is incompatible with live draft {}:{}",
                    project.name(),
                    operationDraft.variantId(),
                    operationDraft.baseVersionId(),
                    liveDraft.variantId(),
                    liveDraft.baseVersionId()
            );
            return Optional.empty();
        }

        StoredChangeAccumulator accumulator = new StoredChangeAccumulator();
        accumulator.addBlockChanges(operationDraft.changes());
        accumulator.addEntityChanges(operationDraft.entityChanges());
        accumulator.addBlockChanges(liveDraft.changes());
        accumulator.addEntityChanges(liveDraft.entityChanges());
        return Optional.of(accumulator.toDraft(
                liveDraft.projectId(),
                liveDraft.variantId(),
                liveDraft.baseVersionId(),
                liveDraft.actor(),
                liveDraft.mutationSource(),
                earliest(operationDraft.startedAt(), liveDraft.startedAt()),
                latest(operationDraft.updatedAt(), liveDraft.updatedAt())
        ));
    }

    private static boolean compatible(RecoveryDraft left, RecoveryDraft right) {
        return Objects.equals(left.projectId(), right.projectId())
                && Objects.equals(left.variantId(), right.variantId())
                && Objects.equals(left.baseVersionId(), right.baseVersionId());
    }

    private static Instant earliest(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private static Instant latest(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }
}
