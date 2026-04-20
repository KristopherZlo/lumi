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
    private final SnapshotRepository snapshotRepository = new SnapshotRepository();

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

        this.snapshotRepository.captureFile(
                baselineFile,
                projectId,
                List.of(chunk),
                level,
                now,
                java.util.Map.of(
                        changedPos.immutable(),
                        new SnapshotRepository.SnapshotBlockState(
                                oldState,
                                oldBlockEntity == null ? null : oldBlockEntity.copy()
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
                if (!name.startsWith(FILE_PREFIX) || !name.endsWith(".nbt.lz4")) {
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
        this.snapshotRepository.restoreFile(this.file(layout, chunk), level);
    }

    public void restoreMissing(ProjectLayout layout, ServerLevel level, Collection<ChunkPoint> restoredChunks) throws IOException {
        Set<ChunkPoint> restored = Set.copyOf(restoredChunks);
        for (ChunkPoint chunk : this.listChunks(layout)) {
            if (restored.contains(chunk)) {
                continue;
            }
            this.restore(layout, chunk, level);
        }
    }

    private Path directory(ProjectLayout layout) {
        return layout.cacheDir().resolve("baseline-chunks");
    }

    private Path file(ProjectLayout layout, ChunkPoint chunk) {
        return this.directory(layout).resolve(FILE_PREFIX + chunk.x() + "_" + chunk.z() + ".nbt.lz4");
    }

    private ChunkPoint parse(String fileName) {
        String body = fileName.substring(FILE_PREFIX.length(), fileName.length() - ".nbt.lz4".length());
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
