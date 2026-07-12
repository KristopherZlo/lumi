package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Isolates the active work-zone portion of a pending save draft. */
final class SaveDraftIsolationService {

    private final WorkZoneService workZoneService = new WorkZoneService();

    ScopedDraftSplit splitForActiveZone(
            ProjectLayout layout,
            RecoveryDraft draft,
            String author
    ) throws IOException {
        Optional<WorkZone> zone = this.workZoneService.activeZone(layout, author)
                .filter(candidate -> !candidate.cells().isEmpty());
        if (zone.isEmpty()) {
            return new ScopedDraftSplit(this.unscoped(draft), null);
        }
        return new ScopedDraftSplit(this.splitForZone(draft, zone.get()), zone.get());
    }

    VersionService.DraftSplit splitForZone(RecoveryDraft draft, WorkZone zone) {
        if (draft == null || zone == null || zone.cells().isEmpty()) {
            return this.unscoped(draft);
        }

        List<StoredBlockChange> selectedBlocks = new ArrayList<>();
        List<StoredBlockChange> remainderBlocks = new ArrayList<>();
        for (StoredBlockChange change : draft.changes()) {
            (zone.contains(WorkZoneCell.from(change.pos())) ? selectedBlocks : remainderBlocks).add(change);
        }

        List<StoredEntityChange> selectedEntities = new ArrayList<>();
        List<StoredEntityChange> remainderEntities = new ArrayList<>();
        for (StoredEntityChange change : draft.entityChanges()) {
            (this.entityTouchesZone(change, zone) ? selectedEntities : remainderEntities).add(change);
        }

        return new VersionService.DraftSplit(
                this.withChanges(draft, selectedBlocks, selectedEntities),
                this.withChanges(draft, remainderBlocks, remainderEntities)
        );
    }

    PendingChangeSummary summarize(RecoveryDraft draft, WorkZone zone) {
        if (draft == null || draft.isEmpty() || zone == null || zone.cells().isEmpty()) {
            return PendingChangeSummary.empty();
        }
        RecoveryDraft selected = this.splitForZone(draft, zone).selected();
        return ChangeStatsFactory.summarizePending(selected.changes(), selected.entityChanges());
    }

    private VersionService.DraftSplit unscoped(RecoveryDraft draft) {
        return new VersionService.DraftSplit(draft, this.withChanges(draft, List.of(), List.of()));
    }

    private boolean entityTouchesZone(StoredEntityChange change, WorkZone zone) {
        return this.entityPayloadTouchesZone(change.oldValue(), zone)
                || this.entityPayloadTouchesZone(change.newValue(), zone);
    }

    private boolean entityPayloadTouchesZone(io.github.luma.domain.model.EntityPayload payload, WorkZone zone) {
        return payload != null && zone.contains(WorkZoneCell.from(BlockPoint.from(payload.blockPos())));
    }

    private RecoveryDraft withChanges(
            RecoveryDraft draft,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) {
        return new RecoveryDraft(
                draft.projectId(),
                draft.variantId(),
                draft.baseVersionId(),
                draft.actor(),
                draft.mutationSource(),
                draft.startedAt(),
                draft.updatedAt(),
                changes,
                entityChanges
        );
    }

    record ScopedDraftSplit(VersionService.DraftSplit split, WorkZone workZone) {
    }
}
