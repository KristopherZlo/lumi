package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves saved block states for an explicit set of positions without
 * expanding a restore into full chunks.
 */
final class BlockTargetStateResolver {

    private static final StatePayload AIR = StatePayload.air();

    private final SnapshotReader snapshotReader = new SnapshotReader();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final VersionLineageService lineageService = new VersionLineageService();

    Map<BlockPoint, StatePayload> resolve(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            List<BlockPoint> positions
    ) throws IOException {
        if (layout == null || targetVersion == null || positions == null || positions.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<BlockPoint, StatePayload> states = new LinkedHashMap<>();
        for (BlockPoint position : positions) {
            if (position != null) {
                states.putIfAbsent(position, AIR);
            }
        }
        if (states.isEmpty()) {
            return Map.of();
        }

        Map<ChunkPoint, List<BlockPoint>> positionsByChunk = positionsByChunk(states.keySet().stream().toList());
        VersionChain chain = this.versionChain(this.lineageService.versionMap(versions), targetVersion);
        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            this.materializeSnapshot(
                    this.snapshotReader.readFile(layout.snapshotFile(chain.anchor().snapshotId()), positionsByChunk.keySet()),
                    positionsByChunk,
                    states
            );
        }
        if (chain.anchor().versionKind() == VersionKind.WORLD_ROOT
                || (project != null && project.tracksWholeDimension())) {
            this.materializeBaseline(layout, positionsByChunk, states);
        }
        this.applyPatchChain(layout, chain.patchVersions(), positionsByChunk, states);
        return Map.copyOf(states);
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
        for (int index = reversed.size() - 1; index >= 0; index -= 1) {
            path.add(reversed.get(index));
        }
        return new VersionChain(cursor, List.copyOf(path));
    }

    private void materializeBaseline(
            ProjectLayout layout,
            Map<ChunkPoint, List<BlockPoint>> positionsByChunk,
            Map<BlockPoint, StatePayload> states
    ) throws IOException {
        for (ChunkPoint chunk : positionsByChunk.keySet()) {
            if (!this.baselineChunkRepository.contains(layout, chunk)) {
                continue;
            }
            this.materializeSnapshot(
                    this.snapshotReader.readFile(this.baselineChunkRepository.filePath(layout, chunk)),
                    positionsByChunk,
                    states
            );
        }
    }

    private void materializeSnapshot(
            SnapshotData snapshot,
            Map<ChunkPoint, List<BlockPoint>> positionsByChunk,
            Map<BlockPoint, StatePayload> states
    ) throws IOException {
        if (snapshot == null) {
            return;
        }
        for (SnapshotChunkData chunk : snapshot.chunks()) {
            List<BlockPoint> positions = positionsByChunk.get(chunk.chunk());
            if (positions == null || positions.isEmpty()) {
                continue;
            }
            Map<Integer, SnapshotSectionData> sections = new LinkedHashMap<>();
            for (SnapshotSectionData section : chunk.sections()) {
                sections.put(section.sectionY(), section);
            }
            for (BlockPoint position : positions) {
                states.put(position, this.payload(snapshot, chunk, sections, position));
            }
        }
    }

    private StatePayload payload(
            SnapshotData snapshot,
            SnapshotChunkData chunk,
            Map<Integer, SnapshotSectionData> sections,
            BlockPoint position
    ) throws IOException {
        if (position.y() < snapshot.minBuildHeight() || position.y() > snapshot.maxBuildHeight()) {
            return AIR;
        }
        int sectionY = Math.floorDiv(position.y(), 16);
        SnapshotSectionData section = sections.get(sectionY);
        if (section == null) {
            return AIR;
        }
        int localX = position.x() & 15;
        int localY = position.y() - (sectionY << 4);
        int localZ = position.z() & 15;
        int localIndex = (localY << 8) | (localZ << 4) | localX;
        if (localIndex < 0 || localIndex >= section.paletteIndexes().length) {
            throw new IOException("Snapshot section index outside palette data");
        }
        int paletteIndex = section.paletteIndexes()[localIndex];
        if (paletteIndex < 0 || paletteIndex >= section.palette().size()) {
            throw new IOException("Snapshot palette index outside palette");
        }
        var stateTag = section.palette().get(paletteIndex);
        var blockEntity = chunk.blockEntities().get(packVerticalIndex(
                position.y() - snapshot.minBuildHeight(),
                localX,
                localZ
        ));
        return new StatePayload(
                stateTag == null ? null : stateTag.copy(),
                blockEntity == null ? null : blockEntity.copy()
        );
    }

    private void applyPatchChain(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            Map<ChunkPoint, List<BlockPoint>> positionsByChunk,
            Map<BlockPoint, StatePayload> states
    ) throws IOException {
        List<ChunkPoint> selectedChunks = positionsByChunk.keySet().stream()
                .sorted(Comparator.comparingInt(ChunkPoint::x).thenComparingInt(ChunkPoint::z))
                .toList();
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds()) {
                var metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                for (StoredBlockChange change : this.patchDataRepository
                        .loadWorldChanges(layout, metadata, selectedChunks)
                        .blockChanges()) {
                    if (states.containsKey(change.pos())) {
                        states.put(change.pos(), change.newValue() == null ? AIR : change.newValue());
                    }
                }
            }
        }
    }

    private static Map<ChunkPoint, List<BlockPoint>> positionsByChunk(List<BlockPoint> positions) {
        Map<ChunkPoint, List<BlockPoint>> grouped = new LinkedHashMap<>();
        for (BlockPoint position : positions) {
            grouped.computeIfAbsent(ChunkPoint.from(position), ignored -> new ArrayList<>()).add(position);
        }
        return grouped;
    }

    private static int packVerticalIndex(int relativeY, int localX, int localZ) {
        return (relativeY << 8) | (localZ << 4) | localX;
    }

    private record VersionChain(ProjectVersion anchor, List<ProjectVersion> patchVersions) {
    }
}
