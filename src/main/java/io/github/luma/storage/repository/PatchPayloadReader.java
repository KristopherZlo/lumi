package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchSectionFrame;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jpountz.lz4.LZ4FrameInputStream;

/**
 * Reads legacy and chunk-addressable patch payloads from disk.
 */
final class PatchPayloadReader {

    private static final int LEGACY_ENTITY_LIST_VERSION = 4;

    private final PatchFrameCompression frameCompression = new PatchFrameCompression();
    private final PatchSectionFrameCodec sectionFrameCodec = new PatchSectionFrameCodec();

    PatchWorldChanges loadWorldChanges(Path dataFile, PatchMetadata metadata) throws IOException {
        if (this.isChunkAddressablePayload(dataFile)) {
            return this.loadChunkAddressableWorldChanges(dataFile);
        }
        return this.loadLegacyWorldChanges(dataFile, metadata);
    }

    PatchWorldChanges loadWorldChanges(
            Path dataFile,
            PatchMetadata metadata,
            Collection<ChunkPoint> chunks
    ) throws IOException {
        if (metadata == null || chunks == null || chunks.isEmpty()) {
            return new PatchWorldChanges(List.of(), List.of());
        }

        Set<ChunkPoint> requestedChunks = new HashSet<>(chunks);
        if (this.isChunkAddressablePayload(dataFile) && metadata.chunks() != null && !metadata.chunks().isEmpty()) {
            return this.loadChunkAddressableWorldChanges(dataFile, metadata, requestedChunks);
        }

        PatchWorldChanges worldChanges = this.loadWorldChanges(dataFile, metadata);
        return new PatchWorldChanges(
                worldChanges.blockChanges().stream()
                        .filter(change -> requestedChunks.contains(ChunkPoint.from(change.pos())))
                        .toList(),
                worldChanges.entityChanges().stream()
                        .filter(change -> requestedChunks.contains(change.chunk()))
                        .toList()
        );
    }

    PatchSectionWorldChanges loadSectionWorldChanges(Path dataFile, PatchMetadata metadata) throws IOException {
        if (!this.isChunkAddressablePayload(dataFile)) {
            return this.sectionFrameCodec.toSectionWorldChanges(this.loadWorldChanges(dataFile, metadata));
        }

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            int version = this.readChunkAddressableHeader(input, dataFile);
            if (version != PatchDataRepository.CURRENT_PAYLOAD_VERSION) {
                return this.sectionFrameCodec.toSectionWorldChanges(this.loadChunkAddressableWorldChanges(dataFile));
            }
            int chunkCount = StorageLimits.requireLength(
                    "patch chunk count",
                    input.readInt(),
                    StorageLimits.MAX_PATCH_CHUNKS
            );
            List<PatchSectionFrame> frames = new ArrayList<>();
            List<StoredEntityChange> entityChanges = new ArrayList<>();
            for (int index = 0; index < chunkCount; index++) {
                PatchSectionWorldChanges chunk = this.readSectionChunkFrame(input, version);
                frames.addAll(chunk.sectionFrames());
                entityChanges.addAll(chunk.entityChanges());
            }
            return new PatchSectionWorldChanges(frames, entityChanges);
        }
    }

    boolean hasReadablePayloadHeader(Path dataFile) {
        try {
            return this.isChunkAddressablePayload(dataFile) || this.hasReadableLegacyPayloadHeader(dataFile);
        } catch (IOException exception) {
            return false;
        }
    }

    private PatchWorldChanges loadLegacyWorldChanges(Path dataFile, PatchMetadata metadata) throws IOException {
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(dataFile))
        ))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != PatchDataRepository.PAYLOAD_MAGIC || !isSupportedLegacyVersion(version)) {
                throw new IOException("Unsupported patch payload format for " + metadata.id());
            }

            int chunkCount = input.readInt();
            List<StoredBlockChange> changes = new ArrayList<>();
            List<StoredEntityChange> entityChanges = new ArrayList<>();
            for (int index = 0; index < chunkCount; index++) {
                PatchWorldChanges chunk = this.readChunk(input, version);
                changes.addAll(chunk.blockChanges());
                entityChanges.addAll(chunk.entityChanges());
                BackgroundThrottle.pauseEvery(index + 1, 8, 250_000L);
            }
            return new PatchWorldChanges(changes, entityChanges);
        }
    }

    private boolean hasReadableLegacyPayloadHeader(Path dataFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(dataFile))
        ))) {
            int magic = input.readInt();
            int version = input.readInt();
            return magic == PatchDataRepository.PAYLOAD_MAGIC && isSupportedLegacyVersion(version);
        }
    }

    private PatchWorldChanges loadChunkAddressableWorldChanges(Path dataFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            int version = this.readChunkAddressableHeader(input, dataFile);
            int chunkCount = StorageLimits.requireLength(
                    "patch chunk count",
                    input.readInt(),
                    StorageLimits.MAX_PATCH_CHUNKS
            );
            List<StoredBlockChange> changes = new ArrayList<>();
            List<StoredEntityChange> entityChanges = new ArrayList<>();
            for (int index = 0; index < chunkCount; index++) {
                PatchWorldChanges chunk = this.readChunkFrame(input, version);
                changes.addAll(chunk.blockChanges());
                entityChanges.addAll(chunk.entityChanges());
                BackgroundThrottle.pauseEvery(index + 1, 8, 250_000L);
            }
            return new PatchWorldChanges(changes, entityChanges);
        }
    }

    private PatchWorldChanges loadChunkAddressableWorldChanges(
            Path dataFile,
            PatchMetadata metadata,
            Set<ChunkPoint> requestedChunks
    ) throws IOException {
        List<PatchChunkSlice> selectedSlices = metadata.chunks().stream()
                .filter(slice -> requestedChunks.contains(slice.chunk()))
                .sorted(Comparator.comparingLong(PatchChunkSlice::dataOffsetBytes))
                .toList();
        if (selectedSlices.isEmpty()) {
            return new PatchWorldChanges(List.of(), List.of());
        }

        List<StoredBlockChange> changes = new ArrayList<>();
        List<StoredEntityChange> entityChanges = new ArrayList<>();
        long fileSize = Files.size(dataFile);
        for (PatchChunkSlice slice : selectedSlices) {
            this.validateSlice(dataFile, slice, fileSize);
        }
        try (RandomAccessFile input = new RandomAccessFile(dataFile.toFile(), "r")) {
            input.seek(4L);
            int version = input.readInt();
            for (PatchChunkSlice slice : selectedSlices) {
                input.seek(slice.dataOffsetBytes());
                PatchWorldChanges chunk = this.readChunkFrame(input, version, slice.chunk());
                changes.addAll(chunk.blockChanges());
                entityChanges.addAll(chunk.entityChanges());
            }
        }
        return new PatchWorldChanges(changes, entityChanges);
    }

    private boolean isChunkAddressablePayload(Path dataFile) throws IOException {
        if (!Files.exists(dataFile) || Files.size(dataFile) < 8L) {
            return false;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            int magic = input.readInt();
            int version = input.readInt();
            return magic == PatchDataRepository.PAYLOAD_MAGIC
                    && (version == PatchDataRepository.CURRENT_PAYLOAD_VERSION
                    || version == PatchDataRepository.HIDDEN_MASK_PAYLOAD_VERSION
                    || version == PatchDataRepository.SECTION_FRAME_PAYLOAD_VERSION
                    || version == PatchDataRepository.CHUNK_ADDRESSABLE_PAYLOAD_VERSION);
        }
    }

    private int readChunkAddressableHeader(DataInputStream input, Path dataFile) throws IOException {
        int magic = input.readInt();
        int version = input.readInt();
        if (magic != PatchDataRepository.PAYLOAD_MAGIC
                || (version != PatchDataRepository.CURRENT_PAYLOAD_VERSION
                && version != PatchDataRepository.HIDDEN_MASK_PAYLOAD_VERSION
                && version != PatchDataRepository.SECTION_FRAME_PAYLOAD_VERSION
                && version != PatchDataRepository.CHUNK_ADDRESSABLE_PAYLOAD_VERSION)) {
            throw new IOException("Unsupported patch payload format for " + dataFile.getFileName());
        }
        return version;
    }

    private static boolean isSupportedLegacyVersion(int version) {
        return version == 3 || version == 4 || version == 5;
    }

    private PatchWorldChanges readChunkFrame(DataInputStream input, int version) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        this.skipFrameIndex(input, version);
        int uncompressedLength = this.readPatchFrameLength(input, "patch chunk frame uncompressed", StorageLimits.MAX_PATCH_FRAME_UNCOMPRESSED_BYTES);
        int compressedLength = this.readPatchFrameLength(input, "patch chunk frame compressed", StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES);
        byte[] compressedBytes = StorageIo.readFullyBounded(
                input,
                compressedLength,
                StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES,
                "patch chunk frame"
        );
        return this.readDecompressedChunkFrame(chunkX, chunkZ, uncompressedLength, compressedBytes, version);
    }

    private PatchWorldChanges readChunkFrame(RandomAccessFile input, int version, ChunkPoint expectedChunk) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        if (expectedChunk != null && (chunkX != expectedChunk.x() || chunkZ != expectedChunk.z())) {
            throw new IOException("Patch selected chunk slice coordinate mismatch");
        }
        this.skipFrameIndex(input, version);
        int uncompressedLength = this.readPatchFrameLength(input, "patch chunk frame uncompressed", StorageLimits.MAX_PATCH_FRAME_UNCOMPRESSED_BYTES);
        int compressedLength = this.readPatchFrameLength(input, "patch chunk frame compressed", StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES);
        byte[] compressedBytes = StorageIo.readFullyBounded(
                input,
                compressedLength,
                StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES,
                "patch chunk frame"
        );
        return this.readDecompressedChunkFrame(chunkX, chunkZ, uncompressedLength, compressedBytes, version);
    }

    private PatchWorldChanges readDecompressedChunkFrame(
            int expectedChunkX,
            int expectedChunkZ,
            int expectedLength,
            byte[] compressedBytes,
            int version
    ) throws IOException {
        byte[] chunkBytes = this.frameCompression.decompress(compressedBytes, expectedLength);
        try (DataInputStream chunkInput = new DataInputStream(new ByteArrayInputStream(chunkBytes))) {
            PatchWorldChanges changes = this.readChunk(chunkInput, version);
            for (StoredBlockChange change : changes.blockChanges()) {
                if ((change.pos().x() >> 4) != expectedChunkX || (change.pos().z() >> 4) != expectedChunkZ) {
                    throw new IOException("Patch chunk frame coordinate mismatch");
                }
            }
            for (StoredEntityChange change : changes.entityChanges()) {
                ChunkPoint entityChunk = change.chunk();
                if (entityChunk.x() != expectedChunkX || entityChunk.z() != expectedChunkZ) {
                    throw new IOException("Patch entity chunk frame coordinate mismatch");
                }
            }
            return changes;
        }
    }

    private PatchWorldChanges readChunk(DataInputStream input, int version) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        int changeCount = StorageLimits.requireLength(
                "patch change count",
                input.readInt(),
                StorageLimits.MAX_PATCH_CHANGES_PER_CHUNK
        );
        if (version >= PatchDataRepository.SECTION_FRAME_PAYLOAD_VERSION) {
            return this.readSectionChunk(chunkX, chunkZ, input, version);
        }
        return this.readPointChunk(chunkX, chunkZ, changeCount, input, version);
    }

    private PatchWorldChanges readPointChunk(
            int chunkX,
            int chunkZ,
            int changeCount,
            DataInputStream input,
            int version
    ) throws IOException {
        List<net.minecraft.nbt.CompoundTag> statePalette = new ArrayList<>();
        int statePaletteCount = StorageLimits.requireLength(
                "patch state palette count",
                input.readInt(),
                StorageLimits.MAX_PALETTE_ENTRIES
        );
        for (int index = 0; index < statePaletteCount; index++) {
            statePalette.add(StorageIo.readCompound(input));
        }

        List<net.minecraft.nbt.CompoundTag> blockEntityPalette = new ArrayList<>();
        int blockEntityPaletteCount = StorageLimits.requireLength(
                "patch block entity palette count",
                input.readInt(),
                StorageLimits.MAX_PALETTE_ENTRIES
        );
        for (int index = 0; index < blockEntityPaletteCount; index++) {
            blockEntityPalette.add(StorageIo.readCompound(input));
        }

        List<StoredBlockChange> changes = new ArrayList<>();
        for (int index = 0; index < changeCount; index++) {
            int packed = input.readInt();
            int oldStateId = input.readInt();
            int newStateId = input.readInt();
            int oldBlockEntityId = input.readInt();
            int newBlockEntityId = input.readInt();
            BlockPoint pos = unpackPosition(chunkX, chunkZ, packed);
            changes.add(new StoredBlockChange(
                    pos,
                    new StatePayload(
                            stateAt("old state palette", statePalette, oldStateId).copy(),
                            blockEntityAt("old block entity palette", blockEntityPalette, oldBlockEntityId)
                    ),
                    new StatePayload(
                            stateAt("new state palette", statePalette, newStateId).copy(),
                            blockEntityAt("new block entity palette", blockEntityPalette, newBlockEntityId)
                    )
            ));
        }
        if (version >= LEGACY_ENTITY_LIST_VERSION) {
            if (version == LEGACY_ENTITY_LIST_VERSION) {
                this.skipEntityLists(input);
            } else {
                return new PatchWorldChanges(changes, this.readEntityChanges(input));
            }
        }
        return new PatchWorldChanges(changes, List.of());
    }

    private PatchWorldChanges readSectionChunk(int chunkX, int chunkZ, DataInputStream input, int version) throws IOException {
        int sectionCount = StorageLimits.requireLength(
                "patch section count",
                input.readInt(),
                StorageLimits.MAX_PATCH_SECTIONS_PER_CHUNK
        );
        List<StoredBlockChange> changes = new ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            PatchSectionFrame frame = this.sectionFrameCodec.readSectionFrame(chunkX, chunkZ, input, version);
            changes.addAll(this.sectionFrameCodec.toStoredChanges(frame));
        }
        return new PatchWorldChanges(changes, this.readEntityChanges(input));
    }

    private PatchSectionWorldChanges readSectionChunkFrame(DataInputStream input, int version) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        this.skipFrameIndex(input, version);
        int uncompressedLength = this.readPatchFrameLength(input, "patch section frame uncompressed", StorageLimits.MAX_PATCH_FRAME_UNCOMPRESSED_BYTES);
        int compressedLength = this.readPatchFrameLength(input, "patch section frame compressed", StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES);
        byte[] compressedBytes = StorageIo.readFullyBounded(
                input,
                compressedLength,
                StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES,
                "patch section frame"
        );
        byte[] chunkBytes = this.frameCompression.decompress(compressedBytes, uncompressedLength);
        try (DataInputStream chunkInput = new DataInputStream(new ByteArrayInputStream(chunkBytes))) {
            int frameChunkX = chunkInput.readInt();
            int frameChunkZ = chunkInput.readInt();
            if (frameChunkX != chunkX || frameChunkZ != chunkZ) {
                throw new IOException("Patch section chunk frame coordinate mismatch");
            }
            chunkInput.readInt();
            int sectionCount = StorageLimits.requireLength(
                    "patch section count",
                    chunkInput.readInt(),
                    StorageLimits.MAX_PATCH_SECTIONS_PER_CHUNK
            );
            List<PatchSectionFrame> frames = new ArrayList<>();
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                frames.add(this.sectionFrameCodec.readSectionFrame(chunkX, chunkZ, chunkInput, version));
            }
            return new PatchSectionWorldChanges(frames, this.readEntityChanges(chunkInput));
        }
    }

    private void skipFrameIndex(DataInput input, int version) throws IOException {
        if (version < PatchDataRepository.CURRENT_PAYLOAD_VERSION) {
            return;
        }
        int sectionCount = StorageLimits.requireLength(
                "patch section fingerprint count",
                input.readInt(),
                StorageLimits.MAX_PATCH_SECTIONS_PER_CHUNK
        );
        for (int index = 0; index < sectionCount; index++) {
            input.readInt();
            input.readInt();
            input.readLong();
            input.readUTF();
        }
        StorageLimits.requireLength(
                "patch entity count",
                input.readInt(),
                StorageLimits.MAX_ENTITY_CHANGES_PER_CHUNK
        );
    }

    private void skipEntityLists(DataInputStream input) throws IOException {
        int spawnCount = input.readInt();
        for (int index = 0; index < spawnCount; index++) {
            StorageIo.readCompound(input);
        }
        int removeCount = input.readInt();
        for (int index = 0; index < removeCount; index++) {
            input.readUTF();
        }
        int updateCount = input.readInt();
        for (int index = 0; index < updateCount; index++) {
            StorageIo.readCompound(input);
        }
    }

    private List<StoredEntityChange> readEntityChanges(DataInputStream input) throws IOException {
        int entityChangeCount = StorageLimits.requireLength(
                "patch entity change count",
                input.readInt(),
                StorageLimits.MAX_ENTITY_CHANGES_PER_CHUNK
        );
        List<StoredEntityChange> changes = new ArrayList<>();
        for (int index = 0; index < entityChangeCount; index++) {
            String entityId = input.readUTF();
            String entityType = input.readUTF();
            net.minecraft.nbt.CompoundTag oldTag = StorageIo.readNullableCompound(input);
            net.minecraft.nbt.CompoundTag newTag = StorageIo.readNullableCompound(input);
            changes.add(new StoredEntityChange(
                    entityId,
                    entityType,
                    oldTag == null ? null : new EntityPayload(oldTag),
                    newTag == null ? null : new EntityPayload(newTag)
            ));
        }
        return changes;
    }

    private int readPatchFrameLength(DataInputStream input, String label, int maxBytes) throws IOException {
        return StorageLimits.requireLength(label, input.readInt(), maxBytes);
    }

    private int readPatchFrameLength(RandomAccessFile input, String label, int maxBytes) throws IOException {
        return StorageLimits.requireLength(label, input.readInt(), maxBytes);
    }

    private void validateSlice(Path dataFile, PatchChunkSlice slice, long fileSize) throws IOException {
        long offset = slice.dataOffsetBytes();
        int length = slice.dataLengthBytes();
        if (offset < 12L || length <= 0 || offset > fileSize || fileSize - offset < length) {
            throw new IOException("Patch chunk slice outside file bounds for " + dataFile.getFileName());
        }
    }

    private net.minecraft.nbt.CompoundTag stateAt(
            String label,
            List<net.minecraft.nbt.CompoundTag> palette,
            int id
    ) throws IOException {
        if (id < 0 || id >= palette.size()) {
            throw new IOException("Invalid " + label + " id " + id);
        }
        return palette.get(id);
    }

    private net.minecraft.nbt.CompoundTag blockEntityAt(
            String label,
            List<net.minecraft.nbt.CompoundTag> palette,
            int id
    ) throws IOException {
        if (id < 0) {
            return null;
        }
        if (id >= palette.size()) {
            throw new IOException("Invalid " + label + " id " + id);
        }
        return palette.get(id).copy();
    }

    private static BlockPoint unpackPosition(int chunkX, int chunkZ, int packed) {
        int normalizedY = packed >>> 8;
        int y = normalizedY + Short.MIN_VALUE;
        int localX = packed & 15;
        int localZ = (packed >>> 4) & 15;
        return new BlockPoint((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
    }
}
