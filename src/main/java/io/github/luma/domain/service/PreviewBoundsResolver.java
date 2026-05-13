package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuilderChangeSurfacePolicy;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.SectionFingerprint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

public final class PreviewBoundsResolver {

    static final int HORIZONTAL_PADDING = 3;
    static final int VERTICAL_PADDING = 2;
    private static final BuilderChangeSurfacePolicy BUILDER_SURFACE = new BuilderChangeSurfacePolicy();

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
        Bounds3i metadataBounds = this.resolvePatchBoundsFromMetadata(
                layout,
                project,
                version,
                level.dimensionType().minY(),
                level.dimensionType().minY() + level.dimensionType().height() - 1
        );
        if (metadataBounds != null) {
            return metadataBounds;
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
            return BUILDER_SURFACE.visibleBlockChanges(draft.changes());
        }

        if (version != null
                && version.patchIds() != null
                && !version.patchIds().isEmpty()
                && !this.hasCompleteVisibleSectionIndex(layout, version.patchIds())) {
            return BUILDER_SURFACE.visibleBlockChanges(this.loadPatchChanges(layout, version.patchIds()));
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
            return ChunkSelectionFactory.fromStoredChanges(BUILDER_SURFACE.visibleBlockChanges(draft.changes()));
        }

        if (version != null && version.patchIds() != null && !version.patchIds().isEmpty()) {
            if (!this.hasCompleteVisibleSectionIndex(layout, version.patchIds())) {
                List<StoredBlockChange> changes =
                        BUILDER_SURFACE.visibleBlockChanges(this.loadPatchChanges(layout, version.patchIds()));
                if (!changes.isEmpty()) {
                    return ChunkSelectionFactory.fromStoredChanges(changes);
                }
                if (this.hasHiddenOnlyPatchChanges(layout, version.patchIds())) {
                    return List.of();
                }
            }
            return this.patchChunksFromMetadata(layout, version.patchIds());
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
            if (!BUILDER_SURFACE.includes(change) || change.pos() == null) {
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

    private Bounds3i resolvePatchBoundsFromMetadata(
            ProjectLayout layout,
            BuildProject project,
            ProjectVersion version,
            int minY,
            int maxY
    ) throws IOException {
        if (version == null || version.patchIds() == null || version.patchIds().isEmpty()) {
            return null;
        }

        List<SectionFingerprint> sections = new ArrayList<>();
        List<ChunkPoint> chunks = new ArrayList<>();
        for (String patchId : version.patchIds()) {
            Optional<PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
            if (metadata.isEmpty()) {
                continue;
            }
            for (var chunk : metadata.get().chunks()) {
                if (!chunk.visibleSectionIndexAvailable()) {
                    return null;
                }
                if (chunk.visibleSectionFingerprints().isEmpty()) {
                    if (chunk.visibleChangeCount() > 0) {
                        chunks.add(chunk.chunk());
                    }
                    continue;
                }
                sections.addAll(chunk.visibleSectionFingerprints());
            }
        }
        if (!sections.isEmpty()) {
            return sectionBounds(
                    sections,
                    project.tracksWholeDimension() ? null : project.bounds(),
                    minY,
                    maxY
            );
        }
        if (!chunks.isEmpty()) {
            return chunkBounds(
                    ChunkSelectionFactory.merge(List.of(), chunks),
                    project.tracksWholeDimension() ? minY : Math.max(minY, project.bounds().min().y()),
                    project.tracksWholeDimension() ? maxY : Math.min(maxY, project.bounds().max().y())
            );
        }
        return null;
    }

    private List<ChunkPoint> patchChunksFromMetadata(ProjectLayout layout, List<String> patchIds) throws IOException {
        Map<String, ChunkPoint> chunks = new LinkedHashMap<>();
        for (String patchId : patchIds) {
            Optional<PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
            if (metadata.isEmpty()) {
                continue;
            }
            for (var chunk : metadata.get().chunks()) {
                if (!chunk.visibleSectionIndexAvailable()
                        || chunk.visibleChangeCount() > 0
                        || chunk.entityCount() > 0) {
                    addChunk(chunks, chunk.chunk());
                }
            }
        }
        return List.copyOf(chunks.values());
    }

    private boolean hasCompleteVisibleSectionIndex(ProjectLayout layout, List<String> patchIds) throws IOException {
        for (String patchId : patchIds == null ? List.<String>of() : patchIds) {
            Optional<PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
            if (metadata.isEmpty()) {
                return false;
            }
            for (var chunk : metadata.get().chunks()) {
                if (!chunk.visibleSectionIndexAvailable()) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<StoredBlockChange> loadPatchChanges(ProjectLayout layout, List<String> patchIds) throws IOException {
        List<StoredBlockChange> changes = new ArrayList<>();
        for (String patchId : patchIds) {
            Optional<PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
            if (metadata.isEmpty()) {
                continue;
            }
            changes.addAll(this.patchDataRepository.loadChanges(layout, metadata.get()));
        }
        return changes;
    }

    private boolean hasHiddenOnlyPatchChanges(ProjectLayout layout, List<String> patchIds) throws IOException {
        List<StoredBlockChange> changes = this.loadPatchChanges(layout, patchIds);
        return !changes.isEmpty() && BUILDER_SURFACE.visibleBlockChanges(changes).isEmpty();
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

        Map<String, ChunkPoint> chunks = new LinkedHashMap<>();
        addChunks(chunks, this.baselineChunkRepository.listChunks(layout));
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds()) {
                Optional<PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
                if (metadata.isEmpty()) {
                    continue;
                }
                for (var chunk : metadata.get().chunks()) {
                    addChunk(chunks, chunk.chunk());
                }
            }
        }

        if (draft == null || draft.isEmpty()) {
            return List.copyOf(chunks.values());
        }

        addChunks(chunks, ChunkSelectionFactory.fromStoredChanges(BUILDER_SURFACE.visibleBlockChanges(draft.changes())));
        return List.copyOf(chunks.values());
    }

    private static void addChunks(Map<String, ChunkPoint> chunks, List<ChunkPoint> source) {
        for (ChunkPoint chunk : source == null ? List.<ChunkPoint>of() : source) {
            addChunk(chunks, chunk);
        }
    }

    private static void addChunk(Map<String, ChunkPoint> chunks, ChunkPoint chunk) {
        if (chunk != null) {
            chunks.putIfAbsent(chunk.x() + ":" + chunk.z(), chunk);
        }
    }

    private static Bounds3i sectionBounds(
            List<SectionFingerprint> sections,
            Bounds3i projectBounds,
            int minY,
            int maxY
    ) {
        int minX = Integer.MAX_VALUE;
        int minSectionY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxSectionY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (SectionFingerprint section : sections) {
            minX = Math.min(minX, section.chunkX() << 4);
            minSectionY = Math.min(minSectionY, section.sectionY() << 4);
            minZ = Math.min(minZ, section.chunkZ() << 4);
            maxX = Math.max(maxX, (section.chunkX() << 4) + 15);
            maxSectionY = Math.max(maxSectionY, (section.sectionY() << 4) + 15);
            maxZ = Math.max(maxZ, (section.chunkZ() << 4) + 15);
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
                        Math.max(clampedMinY, minSectionY - VERTICAL_PADDING),
                        Math.max(clampedMinZ, minZ - HORIZONTAL_PADDING)
                ),
                new BlockPoint(
                        Math.min(clampedMaxX, maxX + HORIZONTAL_PADDING),
                        Math.min(clampedMaxY, maxSectionY + VERTICAL_PADDING),
                        Math.min(clampedMaxZ, maxZ + HORIZONTAL_PADDING)
                )
        );
    }
}
