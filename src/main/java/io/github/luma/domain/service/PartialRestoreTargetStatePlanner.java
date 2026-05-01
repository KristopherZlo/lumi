package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;

/**
 * Builds partial restore changes when the current and target saves do not share
 * a direct patch replay path.
 */
final class PartialRestoreTargetStatePlanner {

    private static final StatePayload AIR = StatePayload.air();

    private final SnapshotReader snapshotReader;
    private final PatchMetaRepository patchMetaRepository;
    private final PatchDataRepository patchDataRepository;
    private final BaselineChunkRepository baselineChunkRepository;
    private final VersionLineageService lineageService;

    PartialRestoreTargetStatePlanner() {
        this(
                new SnapshotReader(),
                new PatchMetaRepository(),
                new PatchDataRepository(),
                new BaselineChunkRepository(),
                new VersionLineageService()
        );
    }

    PartialRestoreTargetStatePlanner(
            SnapshotReader snapshotReader,
            PatchMetaRepository patchMetaRepository,
            PatchDataRepository patchDataRepository,
            BaselineChunkRepository baselineChunkRepository,
            VersionLineageService lineageService
    ) {
        this.snapshotReader = snapshotReader;
        this.patchMetaRepository = patchMetaRepository;
        this.patchDataRepository = patchDataRepository;
        this.baselineChunkRepository = baselineChunkRepository;
        this.lineageService = lineageService;
    }

    Plan plan(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion currentHead,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            Bounds3i bounds,
            PartialRestoreMode mode,
            int worldMinY,
            int worldMaxY,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        if (layout == null || project == null || currentHead == null || targetVersion == null || bounds == null) {
            throw new IllegalArgumentException("Partial restore target-state planning requires project, versions, and bounds");
        }

        Scope scope = this.scope(layout, project, versions, currentHead, targetVersion, bounds, mode, worldMinY, worldMaxY);
        Map<String, ProjectVersion> versionMap = this.lineageService.versionMap(versions);
        progress(progressSink, "Reconstructing current save state");
        VersionState current = this.reconstruct(layout, project, versionMap, currentHead, scope);
        this.applyPendingDraft(current, pendingDraft, scope);
        progress(progressSink, "Reconstructing target save state");
        VersionState target = this.reconstruct(layout, project, versionMap, targetVersion, scope);
        progress(progressSink, "Comparing partial restore target state");
        return new Plan(this.compareBlocks(scope, current, target), this.compareEntities(scope, current, target));
    }

    private Scope scope(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion currentHead,
            ProjectVersion targetVersion,
            Bounds3i bounds,
            PartialRestoreMode mode,
            int worldMinY,
            int worldMaxY
    ) throws IOException {
        PartialRestoreMode effectiveMode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
        if (effectiveMode == PartialRestoreMode.SELECTED_AREA) {
            return new Scope(
                    bounds,
                    bounds,
                    effectiveMode,
                    chunksIntersecting(bounds),
                    bounds.min().y(),
                    bounds.max().y()
            );
        }

        Bounds3i projectBounds = project.bounds();
        int minY = projectBounds == null ? worldMinY : projectBounds.min().y();
        int maxY = projectBounds == null ? worldMaxY : projectBounds.max().y();
        List<ChunkPoint> chunks = project.tracksWholeDimension()
                ? this.knownWholeDimensionChunks(layout, versions, currentHead, targetVersion)
                : chunksIntersecting(projectBounds);
        return new Scope(bounds, projectBounds, effectiveMode, chunks, minY, maxY);
    }

    private List<ChunkPoint> knownWholeDimensionChunks(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion currentHead,
            ProjectVersion targetVersion
    ) throws IOException {
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>(this.baselineChunkRepository.listChunks(layout));
        Map<String, ProjectVersion> versionMap = this.lineageService.versionMap(versions);
        this.addVersionChunks(layout, versionMap, currentHead, chunks);
        this.addVersionChunks(layout, versionMap, targetVersion, chunks);
        return chunks.stream()
                .sorted(Comparator.comparingInt(ChunkPoint::x).thenComparingInt(ChunkPoint::z))
                .toList();
    }

    private void addVersionChunks(
            ProjectLayout layout,
            Map<String, ProjectVersion> versionMap,
            ProjectVersion version,
            Set<ChunkPoint> chunks
    ) throws IOException {
        VersionChain chain = this.versionChain(versionMap, version);
        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            chunks.addAll(this.snapshotReader.loadChunks(layout.snapshotFile(chain.anchor().snapshotId())));
        }
        for (ProjectVersion patchVersion : chain.patchVersions()) {
            for (String patchId : patchVersion.patchIds()) {
                var metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                for (var chunk : metadata.chunks()) {
                    chunks.add(chunk.chunk());
                }
            }
        }
    }

    private VersionState reconstruct(
            ProjectLayout layout,
            BuildProject project,
            Map<String, ProjectVersion> versionMap,
            ProjectVersion version,
            Scope scope
    ) throws IOException {
        VersionChain chain = this.versionChain(versionMap, version);
        VersionState state = new VersionState();
        LinkedHashSet<ChunkPoint> seededChunks = new LinkedHashSet<>();
        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            this.materializeSnapshot(this.snapshotReader.readFile(layout.snapshotFile(chain.anchor().snapshotId())), scope, state, seededChunks);
        }
        if (project.tracksWholeDimension()) {
            this.materializeBaselineGaps(layout, scope, state, seededChunks);
        }
        this.requireSeededChunks(scope, seededChunks, version);
        this.applyPatchChain(layout, chain.patchVersions(), scope, state);
        return state;
    }

    private VersionChain versionChain(Map<String, ProjectVersion> versionMap, ProjectVersion targetVersion) {
        List<ProjectVersion> reversed = new ArrayList<>();
        ProjectVersion cursor = targetVersion;
        while (cursor != null
                && (cursor.snapshotId() == null || cursor.snapshotId().isBlank())
                && cursor.versionKind() != VersionKind.WORLD_ROOT) {
            reversed.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }
        if (cursor == null) {
            throw new IllegalArgumentException("No checkpoint snapshot or world root found for version " + targetVersion.id());
        }
        List<ProjectVersion> path = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return new VersionChain(cursor, List.copyOf(path));
    }

    private void materializeBaselineGaps(
            ProjectLayout layout,
            Scope scope,
            VersionState state,
            Set<ChunkPoint> seededChunks
    ) throws IOException {
        for (ChunkPoint chunk : scope.chunks()) {
            if (seededChunks.contains(chunk)) {
                continue;
            }
            if (!this.baselineChunkRepository.contains(layout, chunk)) {
                continue;
            }
            this.materializeSnapshot(
                    this.snapshotReader.readFile(this.baselineChunkRepository.filePath(layout, chunk)),
                    scope,
                    state,
                    seededChunks
            );
        }
    }

    private void requireSeededChunks(Scope scope, Set<ChunkPoint> seededChunks, ProjectVersion version) {
        List<ChunkPoint> missing = scope.chunks().stream()
                .filter(chunk -> !seededChunks.contains(chunk))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Partial restore target-state plan is missing snapshot or baseline chunks for "
                            + version.id()
                            + ": "
                            + missing
            );
        }
    }

    private void materializeSnapshot(
            SnapshotData snapshot,
            Scope scope,
            VersionState state,
            Set<ChunkPoint> seededChunks
    ) throws IOException {
        for (SnapshotChunkData chunk : snapshot.chunks()) {
            ChunkPoint chunkPoint = chunk.chunk();
            if (!scope.includesChunk(chunkPoint)) {
                continue;
            }
            seededChunks.add(chunkPoint);
            this.materializeChunk(snapshot, chunk, scope, state);
            for (EntityPayload entity : chunk.entitySnapshots()) {
                if (!entity.entityId().isBlank()) {
                    state.entities.put(entity.entityId(), entity);
                }
            }
        }
    }

    private void materializeChunk(
            SnapshotData snapshot,
            SnapshotChunkData chunk,
            Scope scope,
            VersionState state
    ) throws IOException {
        Map<Integer, SnapshotSectionData> sections = new LinkedHashMap<>();
        for (SnapshotSectionData section : chunk.sections()) {
            sections.put(section.sectionY(), section);
        }
        for (int y = scope.minY(); y <= scope.maxY(); y++) {
            int sectionY = Math.floorDiv(y, 16);
            int sectionBaseY = sectionY << 4;
            int localY = y - sectionBaseY;
            SnapshotSectionData section = sections.get(sectionY);
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    BlockPoint pos = new BlockPoint((chunk.chunkX() << 4) + localX, y, (chunk.chunkZ() << 4) + localZ);
                    if (!scope.includesBlock(pos)) {
                        continue;
                    }
                    state.blocks.put(pos, this.payload(snapshot, chunk, section, localY, localX, localZ, y));
                }
            }
        }
    }

    private StatePayload payload(
            SnapshotData snapshot,
            SnapshotChunkData chunk,
            SnapshotSectionData section,
            int localY,
            int localX,
            int localZ,
            int y
    ) throws IOException {
        if (section == null || y < snapshot.minBuildHeight() || y > snapshot.maxBuildHeight()) {
            return AIR;
        }
        int localIndex = (localY << 8) | (localZ << 4) | localX;
        if (localIndex < 0 || localIndex >= section.paletteIndexes().length) {
            throw new IOException("Snapshot section index outside palette data");
        }
        int paletteIndex = section.paletteIndexes()[localIndex];
        if (paletteIndex < 0 || paletteIndex >= section.palette().size()) {
            throw new IOException("Snapshot palette index outside palette");
        }
        CompoundTag stateTag = section.palette().get(paletteIndex);
        CompoundTag blockEntity = chunk.blockEntities().get(packVerticalIndex(y - snapshot.minBuildHeight(), localX, localZ));
        return new StatePayload(
                stateTag == null ? null : stateTag.copy(),
                blockEntity == null ? null : blockEntity.copy()
        );
    }

    private void applyPatchChain(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            Scope scope,
            VersionState state
    ) throws IOException {
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds()) {
                var metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                var changes = this.patchDataRepository.loadWorldChanges(layout, metadata);
                for (StoredBlockChange change : changes.blockChanges()) {
                    if (scope.includesBlock(change.pos())) {
                        state.blocks.put(change.pos(), change.newValue());
                    }
                }
                for (StoredEntityChange change : changes.entityChanges()) {
                    if (!scope.entityMayMatter(change)) {
                        continue;
                    }
                    if (change.newValue() == null) {
                        state.entities.remove(change.entityId());
                    } else {
                        state.entities.put(change.entityId(), change.newValue());
                    }
                }
            }
        }
    }

    private void applyPendingDraft(VersionState state, RecoveryDraft pendingDraft, Scope scope) {
        if (pendingDraft == null || pendingDraft.isEmpty()) {
            return;
        }
        for (StoredBlockChange change : pendingDraft.changes()) {
            if (scope.includesBlock(change.pos())) {
                state.blocks.put(change.pos(), change.newValue());
            }
        }
        for (StoredEntityChange change : pendingDraft.entityChanges()) {
            if (!scope.entityMayMatter(change)) {
                continue;
            }
            if (change.newValue() == null) {
                state.entities.remove(change.entityId());
            } else {
                state.entities.put(change.entityId(), change.newValue());
            }
        }
    }

    private List<StoredBlockChange> compareBlocks(Scope scope, VersionState current, VersionState target) {
        List<StoredBlockChange> changes = new ArrayList<>();
        for (ChunkPoint chunk : scope.chunks()) {
            for (int y = scope.minY(); y <= scope.maxY(); y++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        BlockPoint pos = new BlockPoint((chunk.x() << 4) + localX, y, (chunk.z() << 4) + localZ);
                        if (!scope.includesBlock(pos)) {
                            continue;
                        }
                        StatePayload currentPayload = current.blocks.getOrDefault(pos, AIR);
                        StatePayload targetPayload = target.blocks.getOrDefault(pos, AIR);
                        if (!statesEqual(currentPayload, targetPayload)) {
                            changes.add(new StoredBlockChange(pos, currentPayload, targetPayload));
                        }
                    }
                }
            }
        }
        return List.copyOf(changes);
    }

    private List<StoredEntityChange> compareEntities(Scope scope, VersionState current, VersionState target) {
        LinkedHashSet<String> entityIds = new LinkedHashSet<>();
        entityIds.addAll(current.entities.keySet());
        entityIds.addAll(target.entities.keySet());
        List<StoredEntityChange> changes = new ArrayList<>();
        for (String entityId : entityIds) {
            EntityPayload currentPayload = current.entities.get(entityId);
            EntityPayload targetPayload = target.entities.get(entityId);
            if (Objects.equals(currentPayload, targetPayload) || !scope.includesEntityPair(currentPayload, targetPayload)) {
                continue;
            }
            String entityType = targetPayload == null ? currentPayload.entityType() : targetPayload.entityType();
            changes.add(new StoredEntityChange(entityId, entityType, currentPayload, targetPayload));
        }
        changes.sort(Comparator.comparing(StoredEntityChange::entityId));
        return List.copyOf(changes);
    }

    private static boolean statesEqual(StatePayload left, StatePayload right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equalsState(right);
    }

    private static int packVerticalIndex(int relativeY, int localX, int localZ) {
        return (relativeY << 8) | (localZ << 4) | localX;
    }

    private static List<ChunkPoint> chunksIntersecting(Bounds3i bounds) {
        if (bounds == null) {
            return List.of();
        }
        List<ChunkPoint> chunks = new ArrayList<>();
        int minChunkX = Math.floorDiv(bounds.min().x(), 16);
        int maxChunkX = Math.floorDiv(bounds.max().x(), 16);
        int minChunkZ = Math.floorDiv(bounds.min().z(), 16);
        int maxChunkZ = Math.floorDiv(bounds.max().z(), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkPoint(chunkX, chunkZ));
            }
        }
        return chunks;
    }

    private static void progress(WorldOperationManager.ProgressSink progressSink, String detail) {
        if (progressSink != null) {
            progressSink.update(io.github.luma.domain.model.OperationStage.PREPARING, 0, 1, detail);
        }
    }

    record Plan(List<StoredBlockChange> blockChanges, List<StoredEntityChange> entityChanges) {

        Plan {
            blockChanges = blockChanges == null ? List.of() : List.copyOf(blockChanges);
            entityChanges = entityChanges == null ? List.of() : List.copyOf(entityChanges);
        }
    }

    private record VersionChain(ProjectVersion anchor, List<ProjectVersion> patchVersions) {
    }

    private static final class VersionState {

        private final Map<BlockPoint, StatePayload> blocks = new LinkedHashMap<>();
        private final Map<String, EntityPayload> entities = new LinkedHashMap<>();
    }

    private record Scope(
            Bounds3i bounds,
            Bounds3i limitBounds,
            PartialRestoreMode mode,
            List<ChunkPoint> chunks,
            int minY,
            int maxY
    ) {

        private Scope {
            mode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }

        private boolean includesChunk(ChunkPoint chunk) {
            return this.chunks.contains(chunk);
        }

        private boolean includesBlock(BlockPoint pos) {
            return pos != null
                    && pos.y() >= this.minY
                    && pos.y() <= this.maxY
                    && this.includesChunk(ChunkPoint.from(pos))
                    && (this.limitBounds == null || this.limitBounds.contains(pos))
                    && this.mode.includes(this.bounds.contains(pos));
        }

        private boolean entityMayMatter(StoredEntityChange change) {
            return change != null
                    && (this.includesEntityChunk(change.oldValue()) || this.includesEntityChunk(change.newValue()));
        }

        private boolean includesEntityPair(EntityPayload current, EntityPayload target) {
            if (!this.insideLimit(current) && !this.insideLimit(target)) {
                return false;
            }
            boolean inside = this.insideBounds(current) || this.insideBounds(target);
            return this.mode.includes(inside);
        }

        private boolean includesEntityChunk(EntityPayload payload) {
            return payload != null && this.includesChunk(payload.chunk());
        }

        private boolean insideBounds(EntityPayload payload) {
            return payload != null && this.bounds.contains(BlockPoint.from(payload.blockPos()));
        }

        private boolean insideLimit(EntityPayload payload) {
            return payload != null
                    && (this.limitBounds == null || this.limitBounds.contains(BlockPoint.from(payload.blockPos())));
        }
    }
}
