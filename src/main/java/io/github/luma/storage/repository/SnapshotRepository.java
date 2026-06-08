package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotMetadata;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public final class SnapshotRepository {

    private final SnapshotReader reader = new SnapshotReader();

    public List<ChunkPoint> loadChunks(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.reader.loadChunks(layout, snapshot);
    }

    public List<ChunkPoint> loadChunks(Path snapshotFile) throws IOException {
        return this.reader.loadChunks(snapshotFile);
    }

    public SnapshotData loadChunks(ProjectLayout layout, String snapshotId, Collection<ChunkPoint> chunks) throws IOException {
        return this.reader.readFile(layout.snapshotFile(snapshotId), chunks);
    }

    public SnapshotMetadata loadSectionIndex(ProjectLayout layout, String snapshotId) throws IOException {
        return this.reader.loadSectionIndex(layout.snapshotFile(snapshotId));
    }
}
