package io.github.luma.storage.repository;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.ChunkPayloadSlice;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ContentRef;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SectionFingerprint;
import io.github.luma.domain.model.SectionChangeMask;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotMetadata;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jpountz.lz4.LZ4FrameInputStream;

public final class SnapshotReader {

    private static final int MAGIC = 0x4C534E50;
    private static final int SNAPSHOT_V7 = 7;
    private static final int SNAPSHOT_V8 = 8;

    public SnapshotData load(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.readFile(layout.snapshotFile(snapshot.id()));
    }

    public SnapshotData readFile(Path snapshotFile) throws IOException {
        return this.readAddressableFile(snapshotFile, null);
    }

    public SnapshotData readFile(Path snapshotFile, Collection<ChunkPoint> chunks) throws IOException {
        return this.readAddressableFile(snapshotFile, chunks);
    }

    public List<ChunkPoint> loadChunks(ProjectLayout layout, SnapshotRef snapshot) throws IOException {
        return this.loadChunks(layout.snapshotFile(snapshot.id()));
    }

    public List<ChunkPoint> loadChunks(Path snapshotFile) throws IOException {
        return this.loadAddressableMetadata(snapshotFile).chunks().stream()
                .map(ChunkPayloadSlice::chunk)
                .toList();
    }

    public SnapshotMetadata loadSectionIndex(Path snapshotFile) throws IOException {
        return this.loadAddressableMetadata(snapshotFile);
    }

    boolean hasReadableHeader(Path snapshotFile) {
        try {
            return this.isAddressableSnapshot(snapshotFile);
        } catch (IOException exception) {
            return false;
        }
    }

    private SnapshotData readAddressableFile(Path snapshotFile, Collection<ChunkPoint> chunks) throws IOException {
        Set<ChunkPoint> requested = chunks == null ? null : new HashSet<>(chunks);
        try (RandomAccessFile input = new RandomAccessFile(snapshotFile.toFile(), "r")) {
            AddressableHeader header = this.readAddressableHeader(input, snapshotFile);
            List<SnapshotChunkData> chunkData = new ArrayList<>();
            for (int chunkIndex = 0; chunkIndex < header.chunkCount(); chunkIndex++) {
                long frameOffset = input.getFilePointer();
                AddressableChunkFrame frame = this.readAddressableChunkFrame(input, frameOffset);
                if (requested == null || requested.contains(frame.chunk())) {
                    byte[] compressedBytes = StorageIo.readFullyBounded(
                            input,
                            frame.compressedLength(),
                            StorageLimits.MAX_SNAPSHOT_FRAME_COMPRESSED_BYTES,
                            "snapshot chunk frame"
                    );
                    chunkData.add(this.readDecompressedChunkFrame(header.version(), frame, compressedBytes));
                } else {
                    input.seek(input.getFilePointer() + frame.compressedLength());
                }
            }
            LumaMod.LOGGER.info("Loaded snapshot {} with {} chunks", snapshotFile.getFileName(), chunkData.size());
            return new SnapshotData(
                    header.projectId(),
                    header.createdAt(),
                    header.minY(),
                    header.maxY(),
                    chunkData
            );
        }
    }

    private SnapshotMetadata loadAddressableMetadata(Path snapshotFile) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(snapshotFile.toFile(), "r")) {
            AddressableHeader header = this.readAddressableHeader(input, snapshotFile);
            List<ChunkPayloadSlice> chunks = new ArrayList<>();
            int sectionCount = 0;
            int entityCount = 0;
            for (int chunkIndex = 0; chunkIndex < header.chunkCount(); chunkIndex++) {
                long frameOffset = input.getFilePointer();
                AddressableChunkFrame frame = this.readAddressableChunkFrame(input, frameOffset);
                chunks.add(new ChunkPayloadSlice(
                        frame.chunkX(),
                        frame.chunkZ(),
                        frameOffset,
                        frame.frameLength(),
                        frame.sectionFingerprints(),
                        frame.contentRefs(),
                        frame.entityCount()
                ));
                sectionCount += frame.sectionFingerprints().size();
                entityCount += frame.entityCount();
                input.seek(input.getFilePointer() + frame.compressedLength());
            }
            return new SnapshotMetadata(
                    snapshotId(snapshotFile),
                    header.projectId(),
                    snapshotFile.getFileName().toString(),
                    chunks,
                    sectionCount,
                    entityCount,
                    Files.size(snapshotFile)
            );
        }
    }

    private AddressableHeader readAddressableHeader(RandomAccessFile input, Path snapshotFile) throws IOException {
        int magic = input.readInt();
        int version = input.readInt();
        if (magic != MAGIC || !isSupportedAddressableVersion(version)) {
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
        return new AddressableHeader(version, projectId, createdAt, minY, maxY, chunkCount);
    }

    private AddressableChunkFrame readAddressableChunkFrame(
            RandomAccessFile input,
            long frameOffset
    ) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        int fingerprintCount = StorageLimits.requireLength(
                "snapshot section fingerprint count",
                input.readInt(),
                StorageLimits.MAX_SNAPSHOT_SECTIONS_PER_CHUNK
        );
        List<SectionFingerprint> fingerprints = new ArrayList<>(fingerprintCount);
        List<ContentRef> contentRefs = new ArrayList<>();
        for (int index = 0; index < fingerprintCount; index++) {
            fingerprints.add(new SectionFingerprint(
                    chunkX,
                    chunkZ,
                    input.readInt(),
                    input.readInt(),
                    input.readLong(),
                    input.readUTF()
            ));
            ContentRef contentRef = this.readContentRef(input);
            if (contentRef != null) {
                contentRefs.add(contentRef);
            }
        }
        int entityCount = StorageLimits.requireLength(
                "snapshot entity count",
                input.readInt(),
                StorageLimits.MAX_SNAPSHOT_ENTITY_SNAPSHOTS_PER_CHUNK
        );
        int uncompressedLength = StorageLimits.requireLength(
                "snapshot chunk frame uncompressed",
                input.readInt(),
                StorageLimits.MAX_SNAPSHOT_FRAME_UNCOMPRESSED_BYTES
        );
        int compressedLength = StorageLimits.requireLength(
                "snapshot chunk frame compressed",
                input.readInt(),
                StorageLimits.MAX_SNAPSHOT_FRAME_COMPRESSED_BYTES
        );
        long headerLength = input.getFilePointer() - frameOffset;
        long frameLength = headerLength + compressedLength;
        if (frameLength > Integer.MAX_VALUE) {
            throw new IOException("Snapshot chunk frame length out of bounds: " + frameLength);
        }
        return new AddressableChunkFrame(
                chunkX,
                chunkZ,
                List.copyOf(fingerprints),
                List.copyOf(contentRefs),
                entityCount,
                uncompressedLength,
                compressedLength,
                (int) frameLength
        );
    }

    private SnapshotChunkData readDecompressedChunkFrame(
            int version,
            AddressableChunkFrame frame,
            byte[] compressedBytes
    ) throws IOException {
        byte[] chunkBytes = this.decompressFrame(compressedBytes, frame.uncompressedLength());
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(chunkBytes))) {
            SnapshotChunkData chunk = this.readChunk(version, input);
            if (chunk.chunkX() != frame.chunkX() || chunk.chunkZ() != frame.chunkZ()) {
                throw new IOException("Snapshot chunk frame coordinate mismatch");
            }
            return this.withFrameContentRefs(chunk, frame);
        }
    }

    private ContentRef readContentRef(RandomAccessFile input) throws IOException {
        if (!input.readBoolean()) {
            return null;
        }
        return new ContentRef(
                input.readUTF(),
                input.readUTF(),
                input.readLong(),
                input.readLong()
        );
    }

    private SnapshotChunkData withFrameContentRefs(SnapshotChunkData chunk, AddressableChunkFrame frame) {
        if (frame.contentRefs().isEmpty()) {
            return chunk;
        }
        Map<String, ContentRef> refsBySha = new LinkedHashMap<>();
        for (ContentRef ref : frame.contentRefs()) {
            refsBySha.put(ref.sha256(), ref);
        }
        List<SnapshotSectionData> sections = new ArrayList<>(chunk.sections().size());
        for (SnapshotSectionData section : chunk.sections()) {
            ContentRef contentRef = null;
            for (SectionFingerprint fingerprint : frame.sectionFingerprints()) {
                if (fingerprint.sectionY() == section.sectionY()) {
                    contentRef = refsBySha.get(fingerprint.sha256());
                    break;
                }
            }
            sections.add(new SnapshotSectionData(
                    section.sectionY(),
                    section.palette(),
                    section.bitsPerEntry(),
                    section.packedStorage(),
                    contentRef
            ));
        }
        return new SnapshotChunkData(
                chunk.chunkX(),
                chunk.chunkZ(),
                sections,
                chunk.blockEntities(),
                chunk.entitySnapshots()
        );
    }

    private SnapshotChunkData readChunk(int version, DataInputStream input) throws IOException {
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
            sections.add(this.readSection(version, input));
        }

        Map<Integer, net.minecraft.nbt.CompoundTag> blockEntities = new LinkedHashMap<>();
        for (int blockEntityIndex = 0; blockEntityIndex < blockEntityCount; blockEntityIndex++) {
            blockEntities.put(input.readInt(), StorageIo.readCompound(input));
        }
        return new SnapshotChunkData(chunkX, chunkZ, sections, blockEntities, this.readEntitySnapshots(input));
    }

    private SnapshotSectionData readSection(int version, DataInputStream input) throws IOException {
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
        if (version == SNAPSHOT_V7) {
            return this.readV7Section(input, sectionY, palette);
        }
        return this.readV8Section(input, sectionY, palette);
    }

    private SnapshotSectionData readV7Section(
            DataInputStream input,
            int sectionY,
            List<net.minecraft.nbt.CompoundTag> palette
    ) throws IOException {
        int paletteIndexCount = StorageLimits.requireLength(
                "snapshot palette index count",
                input.readInt(),
                StorageLimits.MAX_SNAPSHOT_PALETTE_INDEXES
        );
        if (paletteIndexCount != SectionChangeMask.ENTRY_COUNT) {
            throw new IOException("Snapshot palette index count mismatch");
        }
        short[] indexes = new short[paletteIndexCount];
        for (int paletteIndex = 0; paletteIndex < paletteIndexCount; paletteIndex++) {
            indexes[paletteIndex] = input.readShort();
            if (indexes[paletteIndex] < 0 || indexes[paletteIndex] >= palette.size()) {
                throw new IOException("Snapshot palette index outside palette");
            }
        }
        return new SnapshotSectionData(sectionY, palette, indexes);
    }

    private SnapshotSectionData readV8Section(
            DataInputStream input,
            int sectionY,
            List<net.minecraft.nbt.CompoundTag> palette
    ) throws IOException {
        if (palette.isEmpty()) {
            throw new IOException("Snapshot section palette is empty");
        }
        int bitsPerEntry = input.readInt();
        if (bitsPerEntry < 0 || bitsPerEntry > Integer.SIZE) {
            throw new IOException("Snapshot packed bits per entry out of bounds");
        }
        int packedLongCount = StorageLimits.requireLength(
                "snapshot packed long count",
                input.readInt(),
                StorageLimits.MAX_SNAPSHOT_PACKED_LONGS
        );
        if (palette.size() == 1) {
            if (bitsPerEntry != 0 || packedLongCount != 0) {
                throw new IOException("Snapshot single-palette section must not store packed data");
            }
            return new SnapshotSectionData(sectionY, palette, 0, new long[0]);
        }
        if (bitsPerEntry <= 0 || (1L << bitsPerEntry) < palette.size()) {
            throw new IOException("Snapshot packed bits cannot address palette");
        }
        int expectedLongCount = SnapshotSectionData.packedLongCount(bitsPerEntry);
        if (packedLongCount != expectedLongCount) {
            throw new IOException("Snapshot packed long count mismatch");
        }
        long[] packedStorage = new long[packedLongCount];
        for (int index = 0; index < packedStorage.length; index++) {
            packedStorage[index] = input.readLong();
        }
        SnapshotSectionData section = new SnapshotSectionData(sectionY, palette, bitsPerEntry, packedStorage);
        for (int localIndex = 0; localIndex < SectionChangeMask.ENTRY_COUNT; localIndex++) {
            int paletteIndex = section.paletteIndexAt(localIndex);
            if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                throw new IOException("Snapshot palette index outside palette");
            }
        }
        return section;
    }

    private List<EntityPayload> readEntitySnapshots(DataInputStream input) throws IOException {
        int entityCount = StorageLimits.requireLength(
                "snapshot entity count",
                input.readInt(),
                StorageLimits.MAX_SNAPSHOT_ENTITY_SNAPSHOTS_PER_CHUNK
        );
        if (entityCount <= 0) {
            return List.of();
        }

        List<EntityPayload> entitySnapshots = new ArrayList<>(entityCount);
        for (int entityIndex = 0; entityIndex < entityCount; entityIndex++) {
            net.minecraft.nbt.CompoundTag tag = StorageIo.readCompound(input);
            entitySnapshots.add(new EntityPayload(tag));
        }
        return entitySnapshots;
    }

    private byte[] decompressFrame(byte[] bytes, int expectedLength) throws IOException {
        StorageLimits.requireLength(
                "snapshot chunk frame uncompressed",
                expectedLength,
                StorageLimits.MAX_SNAPSHOT_FRAME_UNCOMPRESSED_BYTES
        );
        try (LZ4FrameInputStream input = new LZ4FrameInputStream(new ByteArrayInputStream(bytes))) {
            byte[] decompressed = StorageIo.readAllBytesBounded(
                    input,
                    StorageLimits.MAX_SNAPSHOT_FRAME_UNCOMPRESSED_BYTES,
                    "decompressed snapshot frame"
            );
            if (decompressed.length != expectedLength) {
                throw new IOException("Snapshot chunk frame length mismatch");
            }
            return decompressed;
        } catch (IOException exception) {
            throw new IOException("Snapshot chunk frame length mismatch", exception);
        }
    }

    private boolean isAddressableSnapshot(Path snapshotFile) throws IOException {
        if (!Files.exists(snapshotFile) || Files.size(snapshotFile) < 8L) {
            return false;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(snapshotFile)))) {
            return input.readInt() == MAGIC && isSupportedAddressableVersion(input.readInt());
        }
    }

    private static boolean isSupportedAddressableVersion(int version) {
        return version == SNAPSHOT_V7 || version == SNAPSHOT_V8;
    }

    private static String snapshotId(Path snapshotFile) {
        String fileName = snapshotFile.getFileName().toString();
        return fileName.endsWith(".bin.lz4")
                ? fileName.substring(0, fileName.length() - ".bin.lz4".length())
                : fileName;
    }

    private record AddressableHeader(int version, String projectId, Instant createdAt, int minY, int maxY, int chunkCount) {
    }

    private record AddressableChunkFrame(
            int chunkX,
            int chunkZ,
            List<SectionFingerprint> sectionFingerprints,
            List<ContentRef> contentRefs,
            int entityCount,
            int uncompressedLength,
            int compressedLength,
            int frameLength
    ) {

        private AddressableChunkFrame {
            sectionFingerprints = sectionFingerprints == null ? List.of() : List.copyOf(sectionFingerprints);
            contentRefs = contentRefs == null ? List.of() : List.copyOf(contentRefs);
        }

        private ChunkPoint chunk() {
            return new ChunkPoint(this.chunkX, this.chunkZ);
        }
    }
}
