package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public final class BaselineChunkRepository {

    private static final String FILE_PREFIX = "chunk_";
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();

    public boolean writeIfMissing(
            ProjectLayout layout,
            String projectId,
            ChunkSnapshotPayload chunkSnapshot,
            Instant now
    ) throws IOException {
        Path baselineFile = this.file(layout, chunkSnapshot.chunk());
        if (Files.exists(baselineFile)) {
            return false;
        }

        this.snapshotWriter.writePreparedChunkFile(
                layout,
                baselineFile,
                projectId,
                chunkSnapshot,
                now
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

    public Path filePath(ProjectLayout layout, ChunkPoint chunk) {
        return this.file(layout, chunk);
    }

    public int quarantineExcept(ProjectLayout layout, Collection<ChunkPoint> retainedChunks) throws IOException {
        Set<ChunkPoint> retained = new LinkedHashSet<>();
        for (ChunkPoint chunk : retainedChunks == null ? List.<ChunkPoint>of() : retainedChunks) {
            if (chunk != null) {
                retained.add(chunk);
            }
        }

        int moved = 0;
        Path quarantine = layout.cacheDir().resolve("baseline-chunks-orphaned");
        for (ChunkPoint chunk : this.listChunks(layout)) {
            if (retained.contains(chunk)) {
                continue;
            }
            Path source = this.file(layout, chunk);
            Files.createDirectories(quarantine);
            Files.move(
                    source,
                    quarantine.resolve(source.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING
            );
            moved += 1;
        }
        return moved;
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
