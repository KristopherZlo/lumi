package io.github.luma.domain.service;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.List;

/**
 * Resolves the chunk scope for restore summaries targeting root-like versions.
 */
final class RootLikeRestoreChunkResolver {

    private final SnapshotReader snapshotReader;
    private final BaselineChunkRepository baselineChunkRepository;

    RootLikeRestoreChunkResolver(
            SnapshotReader snapshotReader,
            BaselineChunkRepository baselineChunkRepository
    ) {
        this.snapshotReader = snapshotReader;
        this.baselineChunkRepository = baselineChunkRepository;
    }

    List<ChunkPoint> resolve(ProjectLayout layout, ProjectVersion targetVersion) throws IOException {
        if (targetVersion != null
                && targetVersion.versionKind() == VersionKind.INITIAL
                && targetVersion.snapshotId() != null
                && !targetVersion.snapshotId().isBlank()) {
            return this.snapshotReader.loadChunks(layout.snapshotFile(targetVersion.snapshotId())).stream()
                    .map(chunk -> new ChunkPoint(chunk.x(), chunk.z()))
                    .toList();
        }
        return this.baselineChunkRepository.listChunks(layout);
    }
}
