package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies newly written version payloads before their manifest becomes visible. */
final class VersionPayloadVerifier {

    private final PatchMetaRepository patchMetadata = new PatchMetaRepository();
    private final PatchDataRepository patchData = new PatchDataRepository();
    private final SnapshotReader snapshots = new SnapshotReader();

    void verify(
            ProjectLayout layout,
            PatchMetadata expectedMetadata,
            RecoveryDraft expectedDraft,
            String snapshotId,
            String entityCheckpointId
    ) throws IOException {
        if (layout == null || expectedMetadata == null || expectedDraft == null) {
            throw new IllegalArgumentException("Version payload verification requires metadata and draft");
        }
        try {
            PatchMetadata storedMetadata = this.patchMetadata.load(layout, expectedMetadata.id())
                    .orElseThrow(() -> new IOException("Written patch metadata is missing: " + expectedMetadata.id()));
            var storedChanges = this.patchData.loadWorldChanges(layout, storedMetadata);
            if (!blockChanges(expectedDraft).equals(blockChanges(storedChanges.blockChanges()))) {
                throw new IOException("Written patch block payload does not match the isolated draft");
            }
            if (!entityChanges(expectedDraft).equals(entityChanges(storedChanges.entityChanges()))) {
                throw new IOException("Written patch entity payload does not match the isolated draft");
            }
            this.requireReadableSnapshot(layout, snapshotId, false);
            this.requireReadableSnapshot(layout, entityCheckpointId, true);
        } catch (RuntimeException exception) {
            throw new IOException("Written version payload failed verification", exception);
        }
    }

    private void requireReadableSnapshot(
            ProjectLayout layout,
            String payloadId,
            boolean entityCheckpoint
    ) throws IOException {
        if (payloadId == null || payloadId.isBlank()) {
            return;
        }
        var file = entityCheckpoint
                ? layout.entityCheckpointFile(payloadId)
                : layout.snapshotFile(payloadId);
        try {
            this.snapshots.readFile(file);
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Written "
                    + (entityCheckpoint ? "entity checkpoint" : "snapshot")
                    + " is missing or corrupt: " + payloadId, exception);
        }
    }

    private static Map<BlockPoint, StoredBlockChange> blockChanges(RecoveryDraft draft) {
        return blockChanges(draft.changes());
    }

    private static Map<BlockPoint, StoredBlockChange> blockChanges(Iterable<StoredBlockChange> changes) {
        LinkedHashMap<BlockPoint, StoredBlockChange> indexed = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            indexed.put(change.pos(), change);
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, StoredEntityChange> entityChanges(RecoveryDraft draft) {
        return entityChanges(draft.entityChanges());
    }

    private static Map<String, StoredEntityChange> entityChanges(Iterable<StoredEntityChange> changes) {
        LinkedHashMap<String, StoredEntityChange> indexed = new LinkedHashMap<>();
        for (StoredEntityChange change : changes) {
            indexed.put(change.entityId(), change);
        }
        return Map.copyOf(indexed);
    }
}
