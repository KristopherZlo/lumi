package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.jpountz.lz4.LZ4FrameOutputStream;

public final class SnapshotWriter {

    private static final int MAGIC = 0x4C534E50;
    private static final int VERSION = 4;

    public void writeFile(Path snapshotFile, SnapshotData snapshot) throws IOException {
        StorageIo.writeAtomically(snapshotFile, output -> this.writeCompressed(output, snapshot));
    }

    public void writePreparedChunkFile(
            Path snapshotFile,
            String projectId,
            ChunkSnapshotPayload chunk,
            Instant now
    ) throws IOException {
        this.writePreparedChunkFile(snapshotFile, projectId, List.of(chunk), now);
    }

    public void writePreparedChunkFile(
            Path snapshotFile,
            String projectId,
            Collection<ChunkSnapshotPayload> chunks,
            Instant now
    ) throws IOException {
        this.writeFile(snapshotFile, this.materializePreparedSnapshot(projectId, chunks, now));
    }

    public SnapshotRef writePreparedSnapshot(
            ProjectLayout layout,
            String projectId,
            String snapshotId,
            Collection<ChunkSnapshotPayload> chunks,
            Instant now
    ) throws IOException {
        SnapshotData snapshot = this.materializePreparedSnapshot(projectId, chunks, now);
        Path snapshotFile = layout.snapshotFile(snapshotId);
        this.writeFile(snapshotFile, snapshot);
        return new SnapshotRef(
                snapshotId,
                projectId,
                snapshotFile.getFileName().toString(),
                snapshot.chunks().size(),
                Files.size(snapshotFile),
                now
        );
    }

    private void writeCompressed(OutputStream output, SnapshotData snapshot) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new LZ4FrameOutputStream(new BufferedOutputStream(output)))) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeUTF(snapshot.projectId());
            data.writeLong(snapshot.createdAt().toEpochMilli());
            data.writeInt(snapshot.minBuildHeight());
            data.writeInt(snapshot.maxBuildHeight());
            data.writeInt(snapshot.chunks().size());

            for (SnapshotChunkData chunk : snapshot.chunks()) {
                data.writeInt(chunk.chunkX());
                data.writeInt(chunk.chunkZ());
                data.writeInt(chunk.sections().size());
                data.writeInt(chunk.blockEntities().size());

                for (SnapshotSectionData section : chunk.sections()) {
                    data.writeInt(section.sectionY());
                    data.writeInt(section.palette().size());
                    for (var tag : section.palette()) {
                        StorageIo.writeCompound(data, tag);
                    }
                    data.writeInt(section.paletteIndexes().length);
                    for (short paletteIndex : section.paletteIndexes()) {
                        data.writeShort(paletteIndex);
                    }
                }

                for (Map.Entry<Integer, net.minecraft.nbt.CompoundTag> entry : chunk.blockEntities().entrySet()) {
                    data.writeInt(entry.getKey());
                    StorageIo.writeCompound(data, entry.getValue());
                }

                data.writeInt(0); // chunk entity snapshots
            }
        }
    }

    private SnapshotData materializePreparedSnapshot(
            String projectId,
            Collection<ChunkSnapshotPayload> chunks,
            Instant now
    ) {
        List<ChunkSnapshotPayload> orderedChunks = List.copyOf(chunks == null ? List.<ChunkSnapshotPayload>of() : chunks);
        int minBuildHeight = orderedChunks.isEmpty() ? 0 : orderedChunks.getFirst().minBuildHeight();
        int maxBuildHeight = orderedChunks.isEmpty() ? 0 : orderedChunks.getFirst().maxBuildHeight();
        List<SnapshotChunkData> chunkData = new ArrayList<>(orderedChunks.size());
        for (ChunkSnapshotPayload chunk : orderedChunks) {
            minBuildHeight = Math.min(minBuildHeight, chunk.minBuildHeight());
            maxBuildHeight = Math.max(maxBuildHeight, chunk.maxBuildHeight());
            chunkData.add(this.materializePreparedChunk(chunk));
        }
        return new SnapshotData(projectId, now, minBuildHeight, maxBuildHeight, chunkData);
    }

    private SnapshotChunkData materializePreparedChunk(ChunkSnapshotPayload chunk) {
        List<SnapshotSectionData> sections = new ArrayList<>(chunk.sections().size());
        for (ChunkSectionSnapshotPayload section : chunk.sections()) {
            sections.add(new SnapshotSectionData(
                    section.sectionY(),
                    section.palette(),
                    section.unpackPaletteIndexes()
            ));
        }
        return new SnapshotChunkData(chunk.chunkX(), chunk.chunkZ(), sections, chunk.blockEntities());
    }

    public static int packVerticalIndex(int relativeY, int localX, int localZ) {
        return (relativeY << 8) | (localZ << 4) | localX;
    }
}
