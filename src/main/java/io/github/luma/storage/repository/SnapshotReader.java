package io.github.luma.storage.repository;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jpountz.lz4.LZ4FrameInputStream;

public final class SnapshotReader {

    private static final int MAGIC = 0x4C534E50;
    private static final int VERSION = 5;

    public SnapshotData load(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.readFile(layout.snapshotFile(snapshot.id()));
    }

    public SnapshotData readFile(Path snapshotFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(snapshotFile))
        ))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || !isSupportedVersion(version)) {
                throw new IOException("Unsupported snapshot format: " + snapshotFile.getFileName());
            }

            String projectId = input.readUTF();
            Instant createdAt = Instant.ofEpochMilli(input.readLong());
            int minY = input.readInt();
            int maxY = input.readInt();
            int chunkCount = StorageLimits.requireLength(
                    "snapshot chunk count",
                    input.readInt(),
                    StorageLimits.MAX_SNAPSHOT_CHUNKS
            );

            List<SnapshotChunkData> chunks = new ArrayList<>();
            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                int sectionCount = StorageLimits.requireLength(
                        "snapshot section count",
                        input.readInt(),
                        StorageLimits.MAX_SNAPSHOT_SECTIONS_PER_CHUNK
                );
                int blockEntityCount = StorageLimits.requireLength(
                        "snapshot block entity count",
                        input.readInt(),
                        StorageLimits.MAX_SNAPSHOT_BLOCK_ENTITIES_PER_CHUNK
                );

                List<SnapshotSectionData> sections = new ArrayList<>();
                for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                    int sectionY = input.readInt();
                    int paletteSize = StorageLimits.requireLength(
                            "snapshot palette count",
                            input.readInt(),
                            StorageLimits.MAX_PALETTE_ENTRIES
                    );
                    List<net.minecraft.nbt.CompoundTag> palette = new ArrayList<>();
                    for (int paletteIndex = 0; paletteIndex < paletteSize; paletteIndex++) {
                        palette.add(StorageIo.readCompound(input));
                    }
                    int paletteIndexCount = StorageLimits.requireLength(
                            "snapshot palette index count",
                            input.readInt(),
                            StorageLimits.MAX_SNAPSHOT_PALETTE_INDEXES
                    );
                    short[] indexes = new short[paletteIndexCount];
                    for (int paletteIndex = 0; paletteIndex < paletteIndexCount; paletteIndex++) {
                        indexes[paletteIndex] = input.readShort();
                        if (indexes[paletteIndex] < 0 || indexes[paletteIndex] >= paletteSize) {
                            throw new IOException("Snapshot palette index outside palette");
                        }
                    }
                    sections.add(new SnapshotSectionData(sectionY, palette, indexes));
                }

                Map<Integer, net.minecraft.nbt.CompoundTag> blockEntities = new LinkedHashMap<>();
                for (int blockEntityIndex = 0; blockEntityIndex < blockEntityCount; blockEntityIndex++) {
                    blockEntities.put(input.readInt(), StorageIo.readCompound(input));
                }
                List<EntityPayload> entitySnapshots = this.readEntitySnapshots(input, version);

                chunks.add(new SnapshotChunkData(chunkX, chunkZ, sections, blockEntities, entitySnapshots));
                BackgroundThrottle.pauseEvery(chunkIndex + 1, 4, 300_000L);
            }

            LumaMod.LOGGER.info("Loaded snapshot {} with {} chunks", snapshotFile.getFileName(), chunks.size());
            return new SnapshotData(projectId, createdAt, minY, maxY, chunks);
        }
    }

    public List<ChunkPoint> loadChunks(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.loadChunks(layout.snapshotFile(snapshot.id()));
    }

    public List<ChunkPoint> loadChunks(Path snapshotFile) throws IOException {
        List<ChunkPoint> chunks = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(snapshotFile))
        ))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || !isSupportedVersion(version)) {
                throw new IOException("Unsupported snapshot format: " + snapshotFile.getFileName());
            }

            input.readUTF();
            input.readLong();
            input.readInt();
            input.readInt();
            int chunkCount = StorageLimits.requireLength(
                    "snapshot chunk count",
                    input.readInt(),
                    StorageLimits.MAX_SNAPSHOT_CHUNKS
            );

            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                chunks.add(new ChunkPoint(chunkX, chunkZ));

                int sectionCount = StorageLimits.requireLength(
                        "snapshot section count",
                        input.readInt(),
                        StorageLimits.MAX_SNAPSHOT_SECTIONS_PER_CHUNK
                );
                int blockEntityCount = StorageLimits.requireLength(
                        "snapshot block entity count",
                        input.readInt(),
                        StorageLimits.MAX_SNAPSHOT_BLOCK_ENTITIES_PER_CHUNK
                );
                for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                    this.skipSection(input);
                }
                for (int blockEntityIndex = 0; blockEntityIndex < blockEntityCount; blockEntityIndex++) {
                    input.readInt();
                    skipCompound(input);
                }
                if (version >= 4) {
                    int entityCount = StorageLimits.requireLength(
                            "snapshot entity count",
                            input.readInt(),
                            StorageLimits.MAX_SNAPSHOT_ENTITY_SNAPSHOTS_PER_CHUNK
                    );
                    for (int entityIndex = 0; entityIndex < entityCount; entityIndex++) {
                        skipCompound(input);
                    }
                }
                BackgroundThrottle.pauseEvery(chunkIndex + 1, 16, 150_000L);
            }
        }
        return List.copyOf(chunks);
    }

    private void skipSection(DataInputStream input) throws IOException {
        input.readInt();
        int paletteSize = StorageLimits.requireLength(
                "snapshot palette count",
                input.readInt(),
                StorageLimits.MAX_PALETTE_ENTRIES
        );
        for (int paletteIndex = 0; paletteIndex < paletteSize; paletteIndex++) {
            skipCompound(input);
        }
        int paletteIndexCount = StorageLimits.requireLength(
                "snapshot palette index count",
                input.readInt(),
                StorageLimits.MAX_SNAPSHOT_PALETTE_INDEXES
        );
        input.skipNBytes((long) paletteIndexCount * Short.BYTES);
    }

    private List<EntityPayload> readEntitySnapshots(DataInputStream input, int version) throws IOException {
        if (version < 4) {
            return List.of();
        }

        int entityCount = StorageLimits.requireLength(
                "snapshot entity count",
                input.readInt(),
                StorageLimits.MAX_SNAPSHOT_ENTITY_SNAPSHOTS_PER_CHUNK
        );
        if (entityCount <= 0) {
            return List.of();
        }

        List<EntityPayload> entitySnapshots = new ArrayList<>(version >= VERSION ? entityCount : 0);
        for (int entityIndex = 0; entityIndex < entityCount; entityIndex++) {
            net.minecraft.nbt.CompoundTag tag = StorageIo.readCompound(input);
            if (version >= VERSION) {
                entitySnapshots.add(new EntityPayload(tag));
            }
        }
        return entitySnapshots;
    }

    private static void skipCompound(DataInputStream input) throws IOException {
        int length = StorageLimits.requireLength("NBT", input.readInt(), StorageLimits.MAX_NBT_BYTES);
        input.skipNBytes(length);
    }

    private static boolean isSupportedVersion(int version) {
        return version == 3 || version == 4 || version == VERSION;
    }

}
