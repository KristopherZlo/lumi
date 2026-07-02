package io.github.luma.domain.service;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RestoreEntityTypeSelection;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.PreparedChunkBatchCollapser;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;

/**
 * Resolves entity target state for restore and rollback apply paths.
 */
final class RestoreEntityStateResolver {

    private final RestoreChunkCollector chunkCollector;
    private final BaselineChunkRepository baselineChunkRepository;
    private final SnapshotReader snapshotReader;
    private final RestorePayloadLoader payloadLoader;
    private final RestorePlanBuilder restorePlanBuilder;
    private final PreparedChunkBatchCollapser batchCollapser;

    RestoreEntityStateResolver(
            RestoreChunkCollector chunkCollector,
            BaselineChunkRepository baselineChunkRepository,
            SnapshotReader snapshotReader,
            RestorePayloadLoader payloadLoader,
            RestorePlanBuilder restorePlanBuilder,
            PreparedChunkBatchCollapser batchCollapser
    ) {
        this.chunkCollector = chunkCollector;
        this.baselineChunkRepository = baselineChunkRepository;
        this.snapshotReader = snapshotReader;
        this.payloadLoader = payloadLoader;
        this.restorePlanBuilder = restorePlanBuilder;
        this.batchCollapser = batchCollapser;
    }

    RecoveryDraft alignPendingEntityRollbackWithTarget(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft
    ) throws IOException {
        if (pendingDraft == null || pendingDraft.entityChanges().isEmpty()) {
            return pendingDraft;
        }
        if (targetVersion != null && targetVersion.id().equals(pendingDraft.baseVersionId())) {
            return pendingDraft;
        }

        Set<String> entityIds = new HashSet<>();
        for (StoredEntityChange change : pendingDraft.entityChanges()) {
            if (change != null && change.entityId() != null && !change.entityId().isBlank()) {
                entityIds.add(change.entityId());
            }
        }
        if (entityIds.isEmpty()) {
            return pendingDraft;
        }

        Map<String, EntityPayload> targetStates = this.targetEntityStates(
                layout,
                versions,
                targetVersion,
                entityIds,
                this.entityTargetCandidateChunks(pendingDraft.entityChanges())
        );
        List<StoredEntityChange> alignedEntities = pendingDraft.entityChanges().stream()
                .map(change -> new StoredEntityChange(
                        change.entityId(),
                        change.entityType(),
                        targetStates.get(change.entityId()),
                        change.newValue()
                ))
                .filter(change -> !change.isNoOp())
                .toList();
        return new RecoveryDraft(
                pendingDraft.projectId(),
                pendingDraft.variantId(),
                pendingDraft.baseVersionId(),
                pendingDraft.actor(),
                pendingDraft.mutationSource(),
                pendingDraft.startedAt(),
                pendingDraft.updatedAt(),
                pendingDraft.changes(),
                alignedEntities
        );
    }

    List<PreparedChunkBatch> withAuthoritativeEntityReplacementBatches(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            String targetVersionId,
            List<PreparedChunkBatch> batches
    ) throws IOException {
        return this.withAuthoritativeEntityReplacementBatches(
                layout,
                versions,
                targetVersionId,
                batches,
                RestoreEntityTypeSelection.includeAll()
        );
    }

    List<PreparedChunkBatch> withAuthoritativeEntityReplacementBatches(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            String targetVersionId,
            List<PreparedChunkBatch> batches,
            RestoreEntityTypeSelection entityTypeSelection
    ) throws IOException {
        Set<ChunkPoint> selectedChunks = new LinkedHashSet<>(this.chunkCollector.batchChunks(batches));
        selectedChunks.addAll(this.entityCheckpointChunks(layout, versions, targetVersionId));
        if (selectedChunks.isEmpty()) {
            return batches == null ? List.of() : batches;
        }
        List<PreparedChunkBatch> replacementBatches = this.authoritativeEntityReplacementBatches(
                layout,
                versions,
                targetVersionId,
                List.copyOf(selectedChunks),
                entityTypeSelection
        );
        if (replacementBatches.isEmpty()) {
            return batches == null ? List.of() : batches;
        }
        List<PreparedChunkBatch> combined = new ArrayList<>(batches == null ? List.of() : batches);
        combined.addAll(replacementBatches);
        return this.batchCollapser.collapse(combined);
    }

    List<PreparedChunkBatch> authoritativeEntityReplacementBatches(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            String targetVersionId,
            List<ChunkPoint> chunks
    ) throws IOException {
        return this.authoritativeEntityReplacementBatches(
                layout,
                versions,
                targetVersionId,
                chunks,
                RestoreEntityTypeSelection.includeAll()
        );
    }

    List<PreparedChunkBatch> authoritativeEntityReplacementBatches(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            String targetVersionId,
            List<ChunkPoint> chunks,
            RestoreEntityTypeSelection entityTypeSelection
    ) throws IOException {
        if (targetVersionId == null || targetVersionId.isBlank() || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        ProjectVersion targetVersion = versions.stream()
                .filter(version -> version.id().equals(targetVersionId))
                .findFirst()
                .orElse(null);
        if (targetVersion == null) {
            return List.of();
        }

        Set<ChunkPoint> selectedChunks = new LinkedHashSet<>();
        for (ChunkPoint chunk : chunks) {
            if (chunk != null) {
                selectedChunks.add(chunk);
            }
        }
        if (selectedChunks.isEmpty()) {
            return List.of();
        }

        Map<String, EntityPayload> targetStates;
        try {
            targetStates = this.targetEntityStatesForChunks(
                    layout,
                    versions,
                    targetVersion,
                    selectedChunks,
                    entityTypeSelection
            );
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        Map<ChunkPoint, List<CompoundTag>> entitiesByChunk = new LinkedHashMap<>();
        for (ChunkPoint chunk : selectedChunks) {
            entitiesByChunk.put(chunk, new ArrayList<>());
        }
        for (EntityPayload payload : targetStates.values()) {
            if (payload == null || payload.chunk() == null) {
                continue;
            }
            List<CompoundTag> entities = entitiesByChunk.get(payload.chunk());
            if (entities != null) {
                entities.add(payload.copyTag());
            }
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (ChunkPoint chunk : selectedChunks) {
            batches.add(new PreparedChunkBatch(
                    chunk,
                    List.of(),
                    EntityBatch.replaceEntities(
                            entitiesByChunk.getOrDefault(chunk, List.of()),
                            this.excludedEntityTypes(entityTypeSelection)
                    )
            ));
        }
        return batches;
    }

    private Map<String, EntityPayload> targetEntityStates(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            Set<String> entityIds,
            List<ChunkPoint> candidateChunks
    ) throws IOException {
        if (targetVersion == null || entityIds == null || entityIds.isEmpty()) {
            return Map.of();
        }

        RestoreChain chain = this.restorePlanBuilder.resolveChain(versions, targetVersion);
        Map<String, EntityPayload> states = new LinkedHashMap<>();
        if (this.hasEntityCheckpoint(targetVersion)) {
            this.seedEntityCheckpointStates(layout, targetVersion, entityIds, candidateChunks, states, RestoreEntityTypeSelection.includeAll());
            return states;
        }
        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            var snapshot = candidateChunks == null || candidateChunks.isEmpty()
                    ? this.snapshotReader.readFile(layout.snapshotFile(chain.anchor().snapshotId()))
                    : this.snapshotReader.readFile(layout.snapshotFile(chain.anchor().snapshotId()), candidateChunks);
            for (var chunk : snapshot.chunks()) {
                for (EntityPayload entity : chunk.entitySnapshots()) {
                    if (entityIds.contains(entity.entityId())) {
                        states.put(entity.entityId(), entity);
                    }
                }
            }
        } else if (chain.anchor().versionKind() == VersionKind.WORLD_ROOT) {
            this.seedWorldRootEntityStates(layout, entityIds, candidateChunks, states);
        }
        for (ProjectVersion version : chain.patchVersions()) {
            for (StoredEntityChange change : this.payloadLoader.loadVersionEntityChanges(layout, version, entityIds)) {
                if (change.newValue() == null) {
                    states.remove(change.entityId());
                } else {
                    states.put(change.entityId(), change.newValue());
                }
            }
        }
        return states;
    }

    private void seedWorldRootEntityStates(
            ProjectLayout layout,
            Set<String> entityIds,
            List<ChunkPoint> candidateChunks,
            Map<String, EntityPayload> states
    ) throws IOException {
        if (entityIds == null || entityIds.isEmpty() || states == null) {
            return;
        }
        List<ChunkPoint> chunks = candidateChunks == null || candidateChunks.isEmpty()
                ? this.baselineChunkRepository.listChunks(layout)
                : candidateChunks;
        for (ChunkPoint chunk : chunks) {
            if (chunk == null || !this.baselineChunkRepository.contains(layout, chunk)) {
                continue;
            }
            for (var snapshotChunk : this.snapshotReader.readFile(this.baselineChunkRepository.filePath(layout, chunk)).chunks()) {
                for (EntityPayload entity : snapshotChunk.entitySnapshots()) {
                    if (entityIds.contains(entity.entityId())) {
                        states.put(entity.entityId(), entity);
                    }
                }
            }
        }
    }

    private List<ChunkPoint> entityTargetCandidateChunks(List<StoredEntityChange> changes) {
        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        for (StoredEntityChange change : changes == null ? List.<StoredEntityChange>of() : changes) {
            this.addPayloadChunk(chunks, change.oldValue());
            this.addPayloadChunk(chunks, change.newValue());
        }
        return List.copyOf(chunks);
    }

    private void addPayloadChunk(Set<ChunkPoint> chunks, EntityPayload payload) {
        if (payload == null || payload.chunk() == null) {
            return;
        }
        chunks.add(payload.chunk());
    }

    private Map<String, EntityPayload> targetEntityStatesForChunks(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            Iterable<ChunkPoint> selectedChunks
    ) throws IOException {
        return this.targetEntityStatesForChunks(
                layout,
                versions,
                targetVersion,
                selectedChunks,
                RestoreEntityTypeSelection.includeAll()
        );
    }

    private Map<String, EntityPayload> targetEntityStatesForChunks(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            Iterable<ChunkPoint> selectedChunks,
            RestoreEntityTypeSelection entityTypeSelection
    ) throws IOException {
        if (targetVersion == null || selectedChunks == null) {
            return Map.of();
        }
        RestoreEntityTypeSelection selection = entityTypeSelection == null
                ? RestoreEntityTypeSelection.includeAll()
                : entityTypeSelection;

        Set<ChunkPoint> selected = new LinkedHashSet<>();
        for (ChunkPoint chunk : selectedChunks) {
            if (chunk != null) {
                selected.add(chunk);
            }
        }
        if (selected.isEmpty()) {
            return Map.of();
        }

        RestoreChain chain = this.restorePlanBuilder.resolveChain(versions, targetVersion);
        Map<String, EntityPayload> states = new LinkedHashMap<>();
        if (this.hasEntityCheckpoint(targetVersion)) {
            this.seedEntityCheckpointStates(layout, targetVersion, null, selected, states, selection);
            return states;
        }
        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            for (var chunk : this.snapshotReader.readFile(
                    layout.snapshotFile(chain.anchor().snapshotId()),
                    selected
            ).chunks()) {
                if (!selected.contains(new ChunkPoint(chunk.chunkX(), chunk.chunkZ()))) {
                    continue;
                }
                for (EntityPayload entity : chunk.entitySnapshots()) {
                    if (entity == null || !selection.includes(entity.entityType())) {
                        continue;
                    }
                    states.put(entity.entityId(), entity);
                }
            }
        }
        for (ProjectVersion version : chain.patchVersions()) {
            for (StoredEntityChange change : this.payloadLoader.loadVersionEntityChangesForChunks(layout, version, selected)) {
                if (change == null || !selection.includes(change.entityType())) {
                    continue;
                }
                if (change.newValue() == null) {
                    states.remove(change.entityId());
                } else {
                    states.put(change.entityId(), change.newValue());
                }
            }
        }
        return states;
    }

    private void seedEntityCheckpointStates(
            ProjectLayout layout,
            ProjectVersion targetVersion,
            Set<String> entityIds,
            Iterable<ChunkPoint> chunks,
            Map<String, EntityPayload> states,
            RestoreEntityTypeSelection entityTypeSelection
    ) throws IOException {
        if (states == null) {
            return;
        }
        var checkpoint = chunks == null
                ? this.snapshotReader.readFile(layout.entityCheckpointFile(targetVersion.entityCheckpointId()))
                : this.snapshotReader.readFile(layout.entityCheckpointFile(targetVersion.entityCheckpointId()), this.toChunkSet(chunks));
        RestoreEntityTypeSelection selection = entityTypeSelection == null
                ? RestoreEntityTypeSelection.includeAll()
                : entityTypeSelection;
        for (var chunk : checkpoint.chunks()) {
            for (EntityPayload entity : chunk.entitySnapshots()) {
                if (entity == null || entity.entityId().isBlank()) {
                    continue;
                }
                if (entityIds != null && !entityIds.contains(entity.entityId())) {
                    continue;
                }
                if (!selection.includes(entity.entityType())) {
                    continue;
                }
                states.put(entity.entityId(), entity);
            }
        }
    }

    private Set<ChunkPoint> toChunkSet(Iterable<ChunkPoint> chunks) {
        Set<ChunkPoint> selected = new LinkedHashSet<>();
        for (ChunkPoint chunk : chunks == null ? List.<ChunkPoint>of() : chunks) {
            if (chunk != null) {
                selected.add(chunk);
            }
        }
        return selected;
    }

    private boolean hasEntityCheckpoint(ProjectVersion version) {
        return version != null && version.entityCheckpointId() != null && !version.entityCheckpointId().isBlank();
    }

    private List<ChunkPoint> entityCheckpointChunks(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            String targetVersionId
    ) throws IOException {
        ProjectVersion targetVersion = versions == null
                ? null
                : versions.stream()
                        .filter(version -> version.id().equals(targetVersionId))
                        .findFirst()
                        .orElse(null);
        if (layout == null || !this.hasEntityCheckpoint(targetVersion)) {
            return List.of();
        }
        return this.snapshotReader.loadChunks(layout.entityCheckpointFile(targetVersion.entityCheckpointId()));
    }

    private Set<String> excludedEntityTypes(RestoreEntityTypeSelection entityTypeSelection) {
        return entityTypeSelection == null ? Set.of() : entityTypeSelection.excludedEntityTypes();
    }
}
