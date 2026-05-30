package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the snapshot, patch, and baseline sources needed for a restore.
 */
final class RestorePlanBuilder {

    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final RestoreBaselineRequirementValidator baselineRequirementValidator =
            new RestoreBaselineRequirementValidator(this.baselineChunkRepository);
    private final SnapshotReader snapshotReader = new SnapshotReader();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final VersionLineageService lineageService = new VersionLineageService();

    RestorePlan build(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion
    ) throws IOException {
        return this.build(layout, project, versions, targetVersion, List.of());
    }

    RestorePlan build(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            Collection<ChunkPoint> requiredBaselineChunks
    ) throws IOException {
        RestoreChain chain = this.resolveChain(versions, targetVersion);
        LumaDebugLog.log(
                project,
                "restore",
                "Building restore plan for project {} target {} with anchor {}",
                project.name(),
                targetVersion.id(),
                chain.anchor().id()
        );
        Map<String, ChunkPoint> restoredChunks = new LinkedHashMap<>();

        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            for (var chunk : this.snapshotReader.loadChunks(layout.snapshotFile(chain.anchor().snapshotId()))) {
                putChunk(restoredChunks, new ChunkPoint(chunk.x(), chunk.z()));
            }
        }

        List<PatchMetadata> patchMetadata = new ArrayList<>();
        for (ProjectVersion patchVersion : chain.patchVersions()) {
            for (String patchId : patchVersion.patchIds()) {
                PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                patchMetadata.add(metadata);
                for (var chunk : metadata.chunks()) {
                    putChunk(restoredChunks, new ChunkPoint(chunk.chunkX(), chunk.chunkZ()));
                }
            }
        }

        List<ChunkPoint> baselineGaps = this.baselineSources(layout, project, chain, restoredChunks, requiredBaselineChunks);
        LumaDebugLog.log(
                project,
                "restore",
                "Restore plan for project {} resolved {} patch metadata entries and {} baseline gaps",
                project.name(),
                patchMetadata.size(),
                baselineGaps.size()
        );

        return new RestorePlan(chain.anchor(), patchMetadata, baselineGaps);
    }

    RestoreChain resolveChain(List<ProjectVersion> versions, ProjectVersion targetVersion) {
        Map<String, ProjectVersion> versionMap = this.lineageService.versionMap(versions);

        List<ProjectVersion> patchVersions = new ArrayList<>();
        ProjectVersion cursor = targetVersion;
        while (cursor != null
                && (cursor.snapshotId() == null || cursor.snapshotId().isBlank())
                && cursor.versionKind() != VersionKind.WORLD_ROOT) {
            patchVersions.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }

        if (cursor == null) {
            throw new IllegalArgumentException("No checkpoint snapshot found for version " + targetVersion.id());
        }

        patchVersions.sort(Comparator.comparing(ProjectVersion::createdAt));
        LumaMod.LOGGER.info(
                "Resolved restore chain for version {} with anchor {} and {} patch versions",
                targetVersion.id(),
                cursor.id(),
                patchVersions.size()
        );
        return new RestoreChain(cursor, patchVersions);
    }

    private List<ChunkPoint> baselineSources(
            ProjectLayout layout,
            BuildProject project,
            RestoreChain chain,
            Map<String, ChunkPoint> restoredChunks,
            Collection<ChunkPoint> requiredBaselineChunks
    ) throws IOException {
        if (!project.tracksWholeDimension()) {
            return List.of();
        }
        if (chain.anchor().versionKind() == VersionKind.WORLD_ROOT) {
            Map<String, ChunkPoint> chunks = new LinkedHashMap<>(restoredChunks);
            for (ChunkPoint chunk : requiredBaselineChunks == null ? List.<ChunkPoint>of() : requiredBaselineChunks) {
                putChunk(chunks, chunk);
            }
            return this.baselineRequirementValidator.requirePresent(
                    layout,
                    chunks.values(),
                    "world-root restore plan"
            );
        }
        return this.baselineChunkRepository.listMissingChunks(layout, List.copyOf(restoredChunks.values()));
    }

    private static void putChunk(Map<String, ChunkPoint> chunks, ChunkPoint chunk) {
        if (chunk != null) {
            chunks.put(chunk.x() + ":" + chunk.z(), chunk);
        }
    }

}

record RestorePlan(ProjectVersion anchor, List<PatchMetadata> patchChain, List<ChunkPoint> baselineGaps) {
}

record RestoreChain(ProjectVersion anchor, List<ProjectVersion> patchVersions) {
}
