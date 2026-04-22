package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

public final class PreviewBoundsResolver {

    static final int HORIZONTAL_PADDING = 3;
    static final int VERTICAL_PADDING = 2;

    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();

    public Bounds3i resolve(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion version,
            RecoveryDraft draft,
            ServerLevel level
    ) throws IOException {
        List<StoredBlockChange> changes = this.resolvePreviewChanges(layout, project, versions, version, draft);
        Bounds3i changedBounds = changedBlockBounds(
                changes,
                project.tracksWholeDimension() ? null : project.bounds(),
                level.dimensionType().minY(),
                level.dimensionType().minY() + level.dimensionType().height() - 1
        );
        if (changedBounds != null) {
            return changedBounds;
        }

        List<ChunkPoint> chunks = this.resolvePreviewChunks(layout, project, versions, version, draft);
        if (chunks.isEmpty()) {
            if (!project.tracksWholeDimension()) {
                return project.bounds();
            }
            return null;
        }

        return chunkBounds(
                chunks,
                level.dimensionType().minY(),
                level.dimensionType().minY() + level.dimensionType().height() - 1
        );
    }

    List<StoredBlockChange> resolvePreviewChanges(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion version,
            RecoveryDraft draft
    ) throws IOException {
        if (draft != null && !draft.isEmpty()) {
            return List.copyOf(draft.changes());
        }

        if (version != null && version.patchIds() != null && !version.patchIds().isEmpty()) {
            List<StoredBlockChange> changes = this.loadPatchChanges(layout, version.patchIds());
            if (!changes.isEmpty()) {
                return changes;
            }
        }

        if (!project.tracksWholeDimension()) {
            return List.of();
        }

        return List.of();
    }

    List<ChunkPoint> resolvePreviewChunks(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion version,
            RecoveryDraft draft
    ) throws IOException {
        if (draft != null && !draft.isEmpty()) {
            return ChunkSelectionFactory.fromStoredChanges(draft.changes());
        }

        if (version != null && version.patchIds() != null && !version.patchIds().isEmpty()) {
            Map<String, ChunkPoint> chunks = new HashMap<>();
            for (String patchId : version.patchIds()) {
                Optional<io.github.luma.domain.model.PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
                if (metadata.isEmpty()) {
                    continue;
                }
                for (var chunk : metadata.get().chunks()) {
                    ChunkPoint chunkPoint = chunk.chunk();
                    chunks.putIfAbsent(chunkPoint.x() + ":" + chunkPoint.z(), chunkPoint);
                }
            }
            if (!chunks.isEmpty()) {
                return List.copyOf(chunks.values());
            }
        }

        if (!project.tracksWholeDimension()) {
            return ChunkSelectionFactory.fromBounds(project.bounds());
        }

        return this.collectSnapshotChunks(layout, project, versions, draft);
    }

    static Bounds3i changedBlockBounds(
            Collection<StoredBlockChange> changes,
            Bounds3i projectBounds,
            int minY,
            int maxY
    ) {
        if (changes == null || changes.isEmpty()) {
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int minBlockY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxBlockY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (StoredBlockChange change : changes) {
            if (change == null || change.pos() == null) {
                continue;
            }
            BlockPoint pos = change.pos();
            minX = Math.min(minX, pos.x());
            minBlockY = Math.min(minBlockY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x());
            maxBlockY = Math.max(maxBlockY, pos.y());
            maxZ = Math.max(maxZ, pos.z());
        }

        if (minX == Integer.MAX_VALUE) {
            return null;
        }

        int clampedMinY = projectBounds == null
                ? minY
                : Math.max(minY, projectBounds.min().y());
        int clampedMaxY = projectBounds == null
                ? maxY
                : Math.min(maxY, projectBounds.max().y());

        int clampedMinX = projectBounds == null ? Integer.MIN_VALUE : projectBounds.min().x();
        int clampedMaxX = projectBounds == null ? Integer.MAX_VALUE : projectBounds.max().x();
        int clampedMinZ = projectBounds == null ? Integer.MIN_VALUE : projectBounds.min().z();
        int clampedMaxZ = projectBounds == null ? Integer.MAX_VALUE : projectBounds.max().z();

        return new Bounds3i(
                new BlockPoint(
                        Math.max(clampedMinX, minX - HORIZONTAL_PADDING),
                        Math.max(clampedMinY, minBlockY - VERTICAL_PADDING),
                        Math.max(clampedMinZ, minZ - HORIZONTAL_PADDING)
                ),
                new BlockPoint(
                        Math.min(clampedMaxX, maxX + HORIZONTAL_PADDING),
                        Math.min(clampedMaxY, maxBlockY + VERTICAL_PADDING),
                        Math.min(clampedMaxZ, maxZ + HORIZONTAL_PADDING)
                )
        );
    }

    static Bounds3i chunkBounds(List<ChunkPoint> chunks, int minY, int maxY) {
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        for (ChunkPoint chunk : chunks) {
            minChunkX = Math.min(minChunkX, chunk.x());
            maxChunkX = Math.max(maxChunkX, chunk.x());
            minChunkZ = Math.min(minChunkZ, chunk.z());
            maxChunkZ = Math.max(maxChunkZ, chunk.z());
        }

        return new Bounds3i(
                new BlockPoint(minChunkX << 4, minY, minChunkZ << 4),
                new BlockPoint((maxChunkX << 4) + 15, maxY, (maxChunkZ << 4) + 15)
        );
    }

    private List<StoredBlockChange> loadPatchChanges(ProjectLayout layout, List<String> patchIds) throws IOException {
        List<StoredBlockChange> changes = new ArrayList<>();
        for (String patchId : patchIds) {
            Optional<io.github.luma.domain.model.PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
            if (metadata.isEmpty()) {
                continue;
            }
            changes.addAll(this.patchDataRepository.loadChanges(layout, metadata.get()));
        }
        return changes;
    }

    private List<ChunkPoint> collectSnapshotChunks(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            RecoveryDraft draft
    ) throws IOException {
        if (!project.tracksWholeDimension()) {
            return ChunkSelectionFactory.fromBounds(project.bounds());
        }

        List<ChunkPoint> chunks = new ArrayList<>(this.baselineChunkRepository.listChunks(layout));
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds()) {
                Optional<io.github.luma.domain.model.PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
                if (metadata.isEmpty()) {
                    continue;
                }
                for (var chunk : metadata.get().chunks()) {
                    chunks = ChunkSelectionFactory.merge(chunks, List.of(chunk.chunk()));
                }
            }
        }

        if (draft == null || draft.isEmpty()) {
            return List.copyOf(chunks);
        }

        return List.copyOf(ChunkSelectionFactory.merge(chunks, ChunkSelectionFactory.fromStoredChanges(draft.changes())));
    }
}
