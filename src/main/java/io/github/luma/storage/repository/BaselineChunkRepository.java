package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class BaselineChunkRepository {

    private static final String FILE_PREFIX = "chunk_";
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();
    private final SnapshotReader snapshotReader = new SnapshotReader();

    public boolean captureIfMissing(
            ProjectLayout layout,
            String projectId,
            ChunkPoint chunk,
            ServerLevel level,
            Instant now,
            BlockPos changedPos,
            BlockState oldState,
            CompoundTag oldBlockEntity
    ) throws IOException {
        Path baselineFile = this.file(layout, chunk);
        if (Files.exists(baselineFile)) {
            return false;
        }

        this.snapshotWriter.writeFile(
                baselineFile,
                this.snapshotWriter.captureData(
                        projectId,
                        List.of(chunk),
                        level,
                        now,
                        java.util.Map.of(
                                changedPos.immutable(),
                                new SnapshotWriter.SnapshotBlockState(
                                        oldState,
                                        oldBlockEntity == null ? null : oldBlockEntity.copy()
                                )
                        )
                )
        );
        return true;
    }

    public boolean contains(ProjectLayout layout, ChunkPoint chunk) {
        return Files.exists(this.file(layout, chunk));
    }

    public List<ChunkPoint> listChunks(ProjectLayout layout) throws IOException {
        Path directory = this.directory(layout);
        if (!Files.exists(directory)) {
            return List.of();
        }

        List<ChunkPoint> chunks = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(FILE_PREFIX) || !name.endsWith(".bin.lz4")) {
                    continue;
                }
                ChunkPoint chunk = this.parse(name);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
        }
        return chunks;
    }

    public void restore(ProjectLayout layout, ChunkPoint chunk, ServerLevel level) throws IOException {
        for (var batch : this.snapshotReader.decodeBatches(this.file(layout, chunk), level)) {
            io.github.luma.minecraft.world.BlockChangeApplier.applyPreparedBatch(level, batch, 0, batch.placements().size());
        }
    }

    public List<io.github.luma.minecraft.world.PreparedChunkBatch> decodeBatches(
            ProjectLayout layout,
            ChunkPoint chunk,
            ServerLevel level
    ) throws IOException {
        return this.snapshotReader.decodeBatches(this.file(layout, chunk), level);
    }

    public List<ChunkPoint> listMissingChunks(ProjectLayout layout, Collection<ChunkPoint> restoredChunks) throws IOException {
        Set<ChunkPoint> restored = Set.copyOf(restoredChunks);
        List<ChunkPoint> missing = new ArrayList<>();
        for (ChunkPoint chunk : this.listChunks(layout)) {
            if (!restored.contains(chunk)) {
                missing.add(chunk);
            }
        }
        return missing;
    }

    public void restoreMissing(ProjectLayout layout, ServerLevel level, Collection<ChunkPoint> restoredChunks) throws IOException {
        for (ChunkPoint chunk : this.listMissingChunks(layout, restoredChunks)) {
            this.restore(layout, chunk, level);
        }
    }

    public Path filePath(ProjectLayout layout, ChunkPoint chunk) {
        return this.file(layout, chunk);
    }

    private Path directory(ProjectLayout layout) {
        return layout.cacheDir().resolve("baseline-chunks");
    }

    private Path file(ProjectLayout layout, ChunkPoint chunk) {
        return this.directory(layout).resolve(FILE_PREFIX + chunk.x() + "_" + chunk.z() + ".bin.lz4");
    }

    private ChunkPoint parse(String fileName) {
        String body = fileName.substring(FILE_PREFIX.length(), fileName.length() - ".bin.lz4".length());
        String[] parts = body.split("_", 2);
        if (parts.length != 2) {
            return null;
        }

        try {
            return new ChunkPoint(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
