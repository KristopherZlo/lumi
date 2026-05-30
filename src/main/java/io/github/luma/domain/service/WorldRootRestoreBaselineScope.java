package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.util.List;

/**
 * Selects the finite baseline chunk scope for restore plans anchored at the
 * metadata-only WORLD_ROOT version.
 */
final class WorldRootRestoreBaselineScope {

    private final RestorePlanBuilder restorePlanBuilder;
    private final RestoreChunkCollector chunkCollector;

    WorldRootRestoreBaselineScope(
            RestorePlanBuilder restorePlanBuilder,
            RestoreChunkCollector chunkCollector
    ) {
        this.restorePlanBuilder = restorePlanBuilder;
        this.chunkCollector = chunkCollector;
    }

    List<ChunkPoint> resolve(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            List<ProjectVersion> restorePathVersions
    ) throws IOException {
        if (project == null || !project.tracksWholeDimension() || targetVersion == null) {
            return List.of();
        }
        RestoreChain chain = this.restorePlanBuilder.resolveChain(versions, targetVersion);
        if (chain.anchor().versionKind() != VersionKind.WORLD_ROOT) {
            return List.of();
        }
        return this.chunkCollector.touchedChunksForVersions(layout, restorePathVersions);
    }
}
