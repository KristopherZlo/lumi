package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

public final class SnapshotRepository {

    private final SnapshotWriter writer = new SnapshotWriter();
    private final SnapshotReader reader = new SnapshotReader();

    public SnapshotRef capture(
            ProjectLayout layout,
            String projectId,
            String snapshotId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now
    ) throws IOException {
        return this.capture(layout, projectId, snapshotId, chunks, level, now, Map.of());
    }

    public SnapshotRef capture(
            ProjectLayout layout,
            String projectId,
            String snapshotId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now,
            Map<BlockPos, SnapshotBlockState> overrides
    ) throws IOException {
        return this.writer.capture(layout, projectId, snapshotId, chunks, level, now, adaptOverrides(overrides));
    }

    public void captureFile(
            Path snapshotFile,
            String projectId,
            Collection<ChunkPoint> chunks,
            ServerLevel level,
            Instant now,
            Map<BlockPos, SnapshotBlockState> overrides
    ) throws IOException {
        this.writer.writeFile(snapshotFile, this.writer.captureData(projectId, chunks, level, now, adaptOverrides(overrides)));
    }

    public CompoundTag loadSnapshotTag(Path snapshotFile) throws IOException {
        throw new UnsupportedOperationException("Raw snapshot tags are not available for storage v3");
    }

    public List<ChunkPoint> loadChunks(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.reader.loadChunks(layout, snapshot);
    }

    public List<ChunkPoint> loadChunks(Path snapshotFile) throws IOException {
        return this.reader.loadChunks(snapshotFile);
    }

    public record SnapshotBlockState(net.minecraft.world.level.block.state.BlockState state, CompoundTag blockEntityTag) {
    }

    private static Map<BlockPos, SnapshotWriter.SnapshotBlockState> adaptOverrides(Map<BlockPos, SnapshotBlockState> overrides) {
        java.util.LinkedHashMap<BlockPos, SnapshotWriter.SnapshotBlockState> converted = new java.util.LinkedHashMap<>();
        for (Map.Entry<BlockPos, SnapshotBlockState> entry : overrides.entrySet()) {
            converted.put(entry.getKey(), new SnapshotWriter.SnapshotBlockState(entry.getValue().state(), entry.getValue().blockEntityTag()));
        }
        return converted;
    }
}
