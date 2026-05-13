package io.github.luma.domain.service;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Loads patch-backed restore payloads for version-oriented workflows.
 */
final class RestorePayloadLoader {

    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();

    List<StoredBlockChange> loadVersionChanges(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        return this.loadVersionWorldChanges(layout, versions).blockChanges();
    }

    PatchWorldChanges loadVersionWorldChanges(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        return this.loadVersionWorldChanges(layout, versions, null);
    }

    PatchWorldChanges loadVersionWorldChanges(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            List<ChunkPoint> selectedChunks
    ) throws IOException {
        List<StoredBlockChange> changes = new ArrayList<>();
        List<StoredEntityChange> entityChanges = new ArrayList<>();
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds()) {
                PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                PatchWorldChanges worldChanges = selectedChunks == null
                        ? this.patchDataRepository.loadWorldChanges(layout, metadata)
                        : this.patchDataRepository.loadWorldChanges(layout, metadata, selectedChunks);
                changes.addAll(worldChanges.blockChanges());
                entityChanges.addAll(worldChanges.entityChanges());
            }
        }
        return new PatchWorldChanges(changes, entityChanges);
    }

    List<StoredEntityChange> loadVersionEntityChanges(
            ProjectLayout layout,
            ProjectVersion version,
            Set<String> entityIds
    ) throws IOException {
        if (version == null || entityIds == null || entityIds.isEmpty()) {
            return List.of();
        }
        List<StoredEntityChange> entityChanges = new ArrayList<>();
        for (String patchId : version.patchIds()) {
            PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                    .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
            entityChanges.addAll(this.patchDataRepository.loadEntityChanges(layout, metadata, entityIds));
        }
        return entityChanges;
    }

    List<StoredEntityChange> loadVersionEntityChangesForChunks(
            ProjectLayout layout,
            ProjectVersion version,
            Collection<ChunkPoint> chunks
    ) throws IOException {
        if (version == null || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<StoredEntityChange> entityChanges = new ArrayList<>();
        for (String patchId : version.patchIds()) {
            PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                    .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
            entityChanges.addAll(this.patchDataRepository.loadEntityChangesForChunks(layout, metadata, chunks));
        }
        return entityChanges;
    }
}
