package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredChangeAccumulator;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.SnapshotWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reconciles dirty live block sections against their authoritative saved head. */
final class DirtyScopeReconciliationService {

    private static final StatePayload AIR = StatePayload.air();

    private final TargetStateLookup targetStateLookup;
    private final EntityTargetStateLookup entityTargetStateLookup;

    DirtyScopeReconciliationService() {
        this(
                new BlockTargetStateResolver()::resolve,
                new RestoreEntityStateResolver()::targetEntityStatesForChunks
        );
    }

    DirtyScopeReconciliationService(TargetStateLookup targetStateLookup) {
        this(targetStateLookup, (layout, versions, target, chunks) -> Map.of());
    }

    DirtyScopeReconciliationService(
            TargetStateLookup targetStateLookup,
            EntityTargetStateLookup entityTargetStateLookup
    ) {
        this.targetStateLookup = targetStateLookup;
        this.entityTargetStateLookup = entityTargetStateLookup;
    }

    RecoveryDraft reconcileBlocks(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion head,
            ProjectDirtyScope dirtyScope,
            List<ChunkSnapshotPayload> liveChunks,
            RecoveryDraft pendingDraft,
            String actor,
            Instant now
    ) throws IOException {
        this.validate(project, head, dirtyScope, pendingDraft);
        Map<ChunkPoint, ChunkSnapshotPayload> liveByChunk = new LinkedHashMap<>();
        for (ChunkSnapshotPayload chunk : liveChunks == null ? List.<ChunkSnapshotPayload>of() : liveChunks) {
            liveByChunk.put(chunk.chunk(), chunk);
        }

        LinkedHashMap<BlockPoint, StatePayload> liveStates = new LinkedHashMap<>();
        for (ChunkSectionPoint section : dirtyScope.blockSections()) {
            ChunkSnapshotPayload chunk = liveByChunk.get(section.chunk());
            if (chunk == null) {
                throw new IOException("Dirty chunk is unavailable for reconciliation: " + section.chunk());
            }
            this.materializeSection(section, chunk, liveStates);
        }

        Map<BlockPoint, StatePayload> headStates = this.targetStateLookup.resolve(
                layout,
                project,
                versions,
                head,
                List.copyOf(liveStates.keySet())
        );
        if (headStates.size() != liveStates.size()) {
            throw new IOException("Dirty section target state was not fully reconstructed");
        }

        StoredChangeAccumulator changes = new StoredChangeAccumulator();
        if (pendingDraft != null) {
            changes.addBlockChanges(pendingDraft.changes());
            changes.addEntityChanges(pendingDraft.entityChanges());
        }
        for (Map.Entry<BlockPoint, StatePayload> entry : liveStates.entrySet()) {
            StatePayload oldValue = headStates.get(entry.getKey());
            if (oldValue == null) {
                throw new IOException("Dirty position target state was not reconstructed: " + entry.getKey());
            }
            if (!oldValue.equalsState(entry.getValue())) {
                changes.addBlockChange(new StoredBlockChange(entry.getKey(), oldValue, entry.getValue()));
            }
        }
        this.reconcileEntities(layout, versions, head, dirtyScope, liveChunks, changes);

        Instant timestamp = now == null ? Instant.now() : now;
        return new RecoveryDraft(
                dirtyScope.projectId(),
                dirtyScope.variantId(),
                dirtyScope.baseVersionId(),
                pendingDraft == null ? actor : pendingDraft.actor(),
                pendingDraft == null ? WorldMutationSource.SYSTEM : pendingDraft.mutationSource(),
                pendingDraft == null ? timestamp : pendingDraft.startedAt(),
                timestamp,
                changes.blockChanges(),
                changes.entityChanges()
        );
    }

    private void reconcileEntities(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion head,
            ProjectDirtyScope dirtyScope,
            List<ChunkSnapshotPayload> liveChunks,
            StoredChangeAccumulator changes
    ) throws IOException {
        if (dirtyScope.entityChunks().isEmpty()) {
            return;
        }
        LinkedHashMap<String, EntityPayload> liveEntities = new LinkedHashMap<>();
        for (ChunkSnapshotPayload chunk : liveChunks == null ? List.<ChunkSnapshotPayload>of() : liveChunks) {
            if (!dirtyScope.entityChunks().contains(chunk.chunk())) {
                continue;
            }
            for (EntityPayload entity : chunk.entitySnapshots()) {
                if (entity != null && !entity.entityId().isBlank()) {
                    liveEntities.put(entity.entityId(), entity);
                }
            }
        }
        Map<String, EntityPayload> headEntities = this.entityTargetStateLookup.resolve(
                layout,
                versions,
                head,
                dirtyScope.entityChunks()
        );
        Set<String> entityIds = new java.util.LinkedHashSet<>(headEntities.keySet());
        entityIds.addAll(liveEntities.keySet());
        for (String entityId : entityIds) {
            EntityPayload oldValue = headEntities.get(entityId);
            EntityPayload newValue = liveEntities.get(entityId);
            if (!Objects.equals(oldValue, newValue)) {
                String entityType = newValue == null ? oldValue.entityType() : newValue.entityType();
                changes.addEntityChange(new StoredEntityChange(entityId, entityType, oldValue, newValue));
            }
        }
    }

    private void materializeSection(
            ChunkSectionPoint section,
            ChunkSnapshotPayload chunk,
            Map<BlockPoint, StatePayload> states
    ) throws IOException {
        ChunkSectionSnapshotPayload payload = chunk.sections().stream()
                .filter(candidate -> candidate.sectionY() == section.sectionY())
                .findFirst()
                .orElse(null);
        for (int localY = 0; localY < 16; localY++) {
            int y = (section.sectionY() << 4) + localY;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    BlockPoint point = new BlockPoint(
                            (section.chunkX() << 4) + localX,
                            y,
                            (section.chunkZ() << 4) + localZ
                    );
                    states.put(point, this.livePayload(chunk, payload, localX, localY, localZ, y));
                }
            }
        }
    }

    private StatePayload livePayload(
            ChunkSnapshotPayload chunk,
            ChunkSectionSnapshotPayload section,
            int localX,
            int localY,
            int localZ,
            int y
    ) throws IOException {
        if (section == null) {
            return AIR;
        }
        int paletteIndex = section.paletteIndexAt(localX, localY, localZ);
        if (paletteIndex < 0 || paletteIndex >= section.palette().size()) {
            throw new IOException("Live section palette index outside palette");
        }
        var blockEntity = chunk.blockEntities().get(SnapshotWriter.packVerticalIndex(
                y - chunk.minBuildHeight(), localX, localZ
        ));
        return new StatePayload(section.palette().get(paletteIndex).copy(), blockEntity);
    }

    private void validate(
            BuildProject project,
            ProjectVersion head,
            ProjectDirtyScope dirtyScope,
            RecoveryDraft pendingDraft
    ) {
        if (project == null || head == null || dirtyScope == null) {
            throw new IllegalArgumentException("Dirty reconciliation requires project, head, and scope");
        }
        if (!project.id().toString().equals(dirtyScope.projectId())
                || !head.id().equals(dirtyScope.baseVersionId())
                || !head.variantId().equals(dirtyScope.variantId())) {
            throw new IllegalStateException("Dirty scope does not match the active saved head");
        }
        if (pendingDraft != null
                && (!dirtyScope.projectId().equals(pendingDraft.projectId())
                || !dirtyScope.variantId().equals(pendingDraft.variantId())
                || !dirtyScope.baseVersionId().equals(pendingDraft.baseVersionId()))) {
            throw new IllegalStateException("Pending draft and dirty scope do not share one base");
        }
    }

    @FunctionalInterface
    interface TargetStateLookup {
        Map<BlockPoint, StatePayload> resolve(
                ProjectLayout layout,
                BuildProject project,
                List<ProjectVersion> versions,
                ProjectVersion targetVersion,
                List<BlockPoint> positions
        ) throws IOException;
    }

    @FunctionalInterface
    interface EntityTargetStateLookup {
        Map<String, EntityPayload> resolve(
                ProjectLayout layout,
                List<ProjectVersion> versions,
                ProjectVersion targetVersion,
                Iterable<ChunkPoint> chunks
        ) throws IOException;
    }
}
