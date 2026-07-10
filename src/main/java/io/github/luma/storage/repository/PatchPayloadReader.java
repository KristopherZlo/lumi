package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchSectionFrame;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.PatchWorldChanges;
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

/**
 * Reads current chunk-addressable patch payloads from disk.
 */
final class PatchPayloadReader {

    private final PatchFrameCompression frameCompression = new PatchFrameCompression();
    private final PatchSectionFrameCodec sectionFrameCodec = new PatchSectionFrameCodec();

    PatchWorldChanges loadWorldChanges(Path dataFile, PatchMetadata metadata) throws IOException {
        return this.loadChunkAddressableWorldChanges(dataFile);
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
        if (metadata.chunks() != null && !metadata.chunks().isEmpty()) {
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
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            this.readChunkAddressableHeader(input, dataFile);
            int chunkCount = StorageLimits.requireLength(
                    "patch chunk count",
                    input.readInt(),
                    StorageLimits.MAX_PATCH_CHUNKS
            );
            List<PatchSectionFrame> frames = new ArrayList<>();
            List<StoredEntityChange> entityChanges = new ArrayList<>();
            for (int index = 0; index < chunkCount; index++) {
                PatchSectionWorldChanges chunk = this.readSectionChunkFrame(input);
                frames.addAll(chunk.sectionFrames());
                entityChanges.addAll(chunk.entityChanges());
            }
            return new PatchSectionWorldChanges(frames, entityChanges);
        }
    }

    boolean hasReadablePayloadHeader(Path dataFile) {
        try {
            return this.isChunkAddressablePayload(dataFile);
        } catch (IOException exception) {
            return false;
        }
    }

    private PatchWorldChanges loadChunkAddressableWorldChanges(Path dataFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            this.readChunkAddressableHeader(input, dataFile);
            int chunkCount = StorageLimits.requireLength(
                    "patch chunk count",
                    input.readInt(),
                    StorageLimits.MAX_PATCH_CHUNKS
            );
            List<StoredBlockChange> changes = new ArrayList<>();
            List<StoredEntityChange> entityChanges = new ArrayList<>();
            for (int index = 0; index < chunkCount; index++) {
                PatchWorldChanges chunk = this.readChunkFrame(input);
                changes.addAll(chunk.blockChanges());
                entityChanges.addAll(chunk.entityChanges());
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
            if (version != PatchDataRepository.CURRENT_PAYLOAD_VERSION) {
                throw new IOException("Unsupported patch payload format for " + dataFile.getFileName());
            }
            for (PatchChunkSlice slice : selectedSlices) {
                input.seek(slice.dataOffsetBytes());
                PatchWorldChanges chunk = this.readChunkFrame(input, slice.chunk());
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
            return magic == PatchDataRepository.PAYLOAD_MAGIC && version == PatchDataRepository.CURRENT_PAYLOAD_VERSION;
        }
    }

    private void readChunkAddressableHeader(DataInputStream input, Path dataFile) throws IOException {
        int magic = input.readInt();
        int version = input.readInt();
        if (magic != PatchDataRepository.PAYLOAD_MAGIC || version != PatchDataRepository.CURRENT_PAYLOAD_VERSION) {
            throw new IOException("Unsupported patch payload format for " + dataFile.getFileName());
        }
    }

    private PatchWorldChanges readChunkFrame(DataInputStream input) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        this.skipFrameIndex(input);
        int uncompressedLength = this.readPatchFrameLength(input, "patch chunk frame uncompressed", StorageLimits.MAX_PATCH_FRAME_UNCOMPRESSED_BYTES);
        int compressedLength = this.readPatchFrameLength(input, "patch chunk frame compressed", StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES);
        byte[] compressedBytes = StorageIo.readFullyBounded(
                input,
                compressedLength,
                StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES,
                "patch chunk frame"
        );
        return this.readDecompressedChunkFrame(chunkX, chunkZ, uncompressedLength, compressedBytes);
    }

    private PatchWorldChanges readChunkFrame(RandomAccessFile input, ChunkPoint expectedChunk) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        if (expectedChunk != null && (chunkX != expectedChunk.x() || chunkZ != expectedChunk.z())) {
            throw new IOException("Patch selected chunk slice coordinate mismatch");
        }
        this.skipFrameIndex(input);
        int uncompressedLength = this.readPatchFrameLength(input, "patch chunk frame uncompressed", StorageLimits.MAX_PATCH_FRAME_UNCOMPRESSED_BYTES);
        int compressedLength = this.readPatchFrameLength(input, "patch chunk frame compressed", StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES);
        byte[] compressedBytes = StorageIo.readFullyBounded(
                input,
                compressedLength,
                StorageLimits.MAX_PATCH_FRAME_COMPRESSED_BYTES,
                "patch chunk frame"
        );
        return this.readDecompressedChunkFrame(chunkX, chunkZ, uncompressedLength, compressedBytes);
    }

    private PatchWorldChanges readDecompressedChunkFrame(
            int expectedChunkX,
            int expectedChunkZ,
            int expectedLength,
            byte[] compressedBytes
    ) throws IOException {
        byte[] chunkBytes = this.frameCompression.decompress(compressedBytes, expectedLength);
        try (DataInputStream chunkInput = new DataInputStream(new ByteArrayInputStream(chunkBytes))) {
            PatchWorldChanges changes = this.readChunk(chunkInput);
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

    private PatchWorldChanges readChunk(DataInputStream input) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        StorageLimits.requireLength(
                "patch change count",
                input.readInt(),
                StorageLimits.MAX_PATCH_CHANGES_PER_CHUNK
        );
        return this.readSectionChunk(chunkX, chunkZ, input);
    }

    private PatchWorldChanges readSectionChunk(int chunkX, int chunkZ, DataInputStream input) throws IOException {
        int sectionCount = StorageLimits.requireLength(
                "patch section count",
                input.readInt(),
                StorageLimits.MAX_PATCH_SECTIONS_PER_CHUNK
        );
        List<StoredBlockChange> changes = new ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            PatchSectionFrame frame = this.sectionFrameCodec.readSectionFrame(
                    chunkX,
                    chunkZ,
                    input
            );
            changes.addAll(this.sectionFrameCodec.toStoredChanges(frame));
        }
        return new PatchWorldChanges(changes, this.readEntityChanges(input));
    }

    private PatchSectionWorldChanges readSectionChunkFrame(DataInputStream input) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        this.skipFrameIndex(input);
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
                frames.add(this.sectionFrameCodec.readSectionFrame(chunkX, chunkZ, chunkInput));
            }
            return new PatchSectionWorldChanges(frames, this.readEntityChanges(chunkInput));
        }
    }

    private void skipFrameIndex(DataInput input) throws IOException {
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

}
