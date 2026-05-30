package io.github.luma.domain.service;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
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
        List<ChunkPoint> chunks = this.chunkCollector.batchChunks(batches);
        if (chunks.isEmpty()) {
            return batches == null ? List.of() : batches;
        }
        List<PreparedChunkBatch> replacementBatches = this.authoritativeEntityReplacementBatches(
                layout,
                versions,
                targetVersionId,
                chunks
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

        Map<String, ChunkPoint> selectedChunks = new LinkedHashMap<>();
        for (ChunkPoint chunk : chunks) {
            if (chunk != null) {
                selectedChunks.put(chunkKey(chunk), chunk);
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
                    selectedChunks.values()
            );
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        Map<String, List<CompoundTag>> entitiesByChunk = new LinkedHashMap<>();
        for (ChunkPoint chunk : selectedChunks.values()) {
            entitiesByChunk.put(chunkKey(chunk), new ArrayList<>());
        }
        for (EntityPayload payload : targetStates.values()) {
            if (payload == null || payload.chunk() == null) {
                continue;
            }
            String chunkKey = chunkKey(payload.chunk());
            List<CompoundTag> entities = entitiesByChunk.get(chunkKey);
            if (entities != null) {
                entities.add(payload.copyTag());
            }
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (ChunkPoint chunk : selectedChunks.values()) {
            batches.add(new PreparedChunkBatch(
                    chunk,
                    List.of(),
                    EntityBatch.replacePlacedEntities(entitiesByChunk.getOrDefault(chunkKey(chunk), List.of()))
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
        Map<String, ChunkPoint> chunks = new LinkedHashMap<>();
        for (StoredEntityChange change : changes == null ? List.<StoredEntityChange>of() : changes) {
            this.addPayloadChunk(chunks, change.oldValue());
            this.addPayloadChunk(chunks, change.newValue());
        }
        return List.copyOf(chunks.values());
    }

    private void addPayloadChunk(Map<String, ChunkPoint> chunks, EntityPayload payload) {
        if (payload == null || payload.chunk() == null) {
            return;
        }
        ChunkPoint chunk = payload.chunk();
        chunks.putIfAbsent(chunkKey(chunk), chunk);
    }

    private Map<String, EntityPayload> targetEntityStatesForChunks(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            Iterable<ChunkPoint> selectedChunks
    ) throws IOException {
        if (targetVersion == null || selectedChunks == null) {
            return Map.of();
        }

        Map<String, ChunkPoint> selected = new LinkedHashMap<>();
        for (ChunkPoint chunk : selectedChunks) {
            if (chunk != null) {
                selected.put(chunkKey(chunk), chunk);
            }
        }
        if (selected.isEmpty()) {
            return Map.of();
        }
        Set<String> selectedChunkKeys = selected.keySet();

        RestoreChain chain = this.restorePlanBuilder.resolveChain(versions, targetVersion);
        Map<String, EntityPayload> states = new LinkedHashMap<>();
        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            for (var chunk : this.snapshotReader.readFile(
                    layout.snapshotFile(chain.anchor().snapshotId()),
                    selected.values()
            ).chunks()) {
                if (!selectedChunkKeys.contains(chunkKey(chunk.chunkX(), chunk.chunkZ()))) {
                    continue;
                }
                for (EntityPayload entity : chunk.entitySnapshots()) {
                    states.put(entity.entityId(), entity);
                }
            }
        }
        for (ProjectVersion version : chain.patchVersions()) {
            for (StoredEntityChange change : this.payloadLoader.loadVersionEntityChangesForChunks(layout, version, selected.values())) {
                if (change.newValue() == null) {
                    states.remove(change.entityId());
                } else {
                    states.put(change.entityId(), change.newValue());
                }
            }
        }
        return states;
    }

    private static String chunkKey(ChunkPoint chunk) {
        return chunkKey(chunk.x(), chunk.z());
    }

    private static String chunkKey(int chunkX, int chunkZ) {
        return chunkX + ":" + chunkZ;
    }
}
