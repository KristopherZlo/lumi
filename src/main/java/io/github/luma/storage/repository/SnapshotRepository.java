package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

public final class SnapshotRepository {

    private final SnapshotReader reader = new SnapshotReader();

    public CompoundTag loadSnapshotTag(Path snapshotFile) throws IOException {
        throw new UnsupportedOperationException("Raw snapshot tags are not available for storage v3");
    }

    public List<ChunkPoint> loadChunks(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.reader.loadChunks(layout, snapshot);
    }

    public List<ChunkPoint> loadChunks(Path snapshotFile) throws IOException {
        return this.reader.loadChunks(snapshotFile);
    }
}
