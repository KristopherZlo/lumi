package io.github.luma.storage.repository;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.ProjectLayout;
import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

public final class PatchDataRepository {

    private static final int MAGIC = 0x4C504154;
    private static final int VERSION = 6;

    public PatchMetadata writePayload(
            ProjectLayout layout,
            String patchId,
            String projectId,
            String versionId,
            List<StoredBlockChange> changes
    ) throws IOException {
        return this.writePayload(layout, patchId, projectId, versionId, changes, List.of());
    }

    public PatchMetadata writePayload(
            ProjectLayout layout,
            String patchId,
            String projectId,
            String versionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) throws IOException {
        changes = changes == null ? List.of() : changes;
        entityChanges = entityChanges == null ? List.of() : entityChanges;
        Map<String, ChunkPayload> grouped = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            String key = chunkKey(change);
            grouped.computeIfAbsent(key, ignored -> new ChunkPayload()).blockChanges.add(change);
        }
        for (StoredEntityChange change : entityChanges) {
            String key = chunkKey(change);
            grouped.computeIfAbsent(key, ignored -> new ChunkPayload()).entityChanges.add(change);
        }

        List<Map.Entry<String, ChunkPayload>> sortedChunks = new ArrayList<>(grouped.entrySet());
        sortedChunks.sort(Comparator.comparing(Map.Entry<String, ChunkPayload>::getKey));
        LumaMod.LOGGER.info(
                "Writing patch payload {} with {} block changes and {} entity changes across {} chunks",
                patchId,
                changes.size(),
                entityChanges.size(),
                sortedChunks.size()
        );

        List<ChunkFrame> frames = new ArrayList<>();
        int chunkIndex = 0;
        for (Map.Entry<String, ChunkPayload> entry : sortedChunks) {
            String[] split = entry.getKey().split(":", 2);
            int chunkX = Integer.parseInt(split[0]);
            int chunkZ = Integer.parseInt(split[1]);

            List<StoredBlockChange> chunkChanges = new ArrayList<>(entry.getValue().blockChanges);
            chunkChanges.sort(Comparator.comparingInt(change -> packLocalPosition(change.pos())));
            List<StoredEntityChange> chunkEntityChanges = new ArrayList<>(entry.getValue().entityChanges);
            chunkEntityChanges.sort(Comparator.comparing(StoredEntityChange::entityId));

            byte[] chunkBytes = this.writeChunk(chunkX, chunkZ, chunkChanges, chunkEntityChanges);
            frames.add(new ChunkFrame(chunkX, chunkZ, chunkChanges.size(), chunkBytes.length, this.compressFrame(chunkBytes)));
            chunkIndex += 1;
            BackgroundThrottle.pauseEvery(chunkIndex, 8, 250_000L);
        }

        List<PatchChunkSlice> slices = new ArrayList<>();
        StorageIo.writeAtomically(layout.patchDataFile(patchId), output -> this.writeChunkAddressablePayload(output, frames, slices));
        return new PatchMetadata(
                patchId,
                projectId,
                versionId,
                layout.patchDataFile(patchId).getFileName().toString(),
                List.copyOf(slices),
                new PatchStats(changes.size(), slices.size())
        );
    }

    public List<StoredBlockChange> loadChanges(ProjectLayout layout, PatchMetadata metadata) throws IOException {
        return this.loadWorldChanges(layout, metadata).blockChanges();
    }

    public PatchWorldChanges loadWorldChanges(ProjectLayout layout, PatchMetadata metadata) throws IOException {
        if (metadata == null) {
            return new PatchWorldChanges(List.of(), List.of());
        }

        Path dataFile = layout.patchDataFile(metadata.id());
        if (this.isChunkAddressablePayload(dataFile)) {
            return this.loadChunkAddressableWorldChanges(dataFile);
        }

        return this.loadLegacyWorldChanges(dataFile, metadata);
    }

    public PatchWorldChanges loadWorldChanges(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<ChunkPoint> chunks
    ) throws IOException {
        if (metadata == null || chunks == null || chunks.isEmpty()) {
            return new PatchWorldChanges(List.of(), List.of());
        }

        Set<ChunkPoint> requestedChunks = new HashSet<>(chunks);
        Path dataFile = layout.patchDataFile(metadata.id());
        if (this.isChunkAddressablePayload(dataFile) && metadata.chunks() != null && !metadata.chunks().isEmpty()) {
            return this.loadChunkAddressableWorldChanges(dataFile, metadata, requestedChunks);
        }

        PatchWorldChanges worldChanges = this.loadWorldChanges(layout, metadata);
        return new PatchWorldChanges(
                worldChanges.blockChanges().stream()
                        .filter(change -> requestedChunks.contains(ChunkPoint.from(change.pos())))
                        .toList(),
                worldChanges.entityChanges().stream()
                        .filter(change -> requestedChunks.contains(change.chunk()))
                        .toList()
        );
    }

    private PatchWorldChanges loadLegacyWorldChanges(Path dataFile, PatchMetadata metadata) throws IOException {
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(dataFile))
        ))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || (version != 3 && version != 4 && version != 5)) {
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

    private PatchWorldChanges loadChunkAddressableWorldChanges(Path dataFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            this.readChunkAddressableHeader(input, dataFile);
            int chunkCount = input.readInt();
            List<StoredBlockChange> changes = new ArrayList<>();
            List<StoredEntityChange> entityChanges = new ArrayList<>();
            for (int index = 0; index < chunkCount; index++) {
                PatchWorldChanges chunk = this.readChunkFrame(input);
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
        try (RandomAccessFile input = new RandomAccessFile(dataFile.toFile(), "r")) {
            for (PatchChunkSlice slice : selectedSlices) {
                input.seek(slice.dataOffsetBytes());
                PatchWorldChanges chunk = this.readChunkFrame(input);
                changes.addAll(chunk.blockChanges());
                entityChanges.addAll(chunk.entityChanges());
            }
        }
        return new PatchWorldChanges(changes, entityChanges);
    }

    private byte[] writeChunk(
            int chunkX,
            int chunkZ,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) throws IOException {
        ByteArrayOutputStream chunkBuffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(chunkBuffer)) {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            output.writeInt(changes.size());

            LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> statePalette = new LinkedHashMap<>();
            LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> blockEntityPalette = new LinkedHashMap<>();
            for (StoredBlockChange change : changes) {
                this.paletteId(statePalette, change.oldValue().stateTag());
                this.paletteId(statePalette, change.newValue().stateTag());
                this.paletteId(blockEntityPalette, change.oldValue().blockEntityTag());
                this.paletteId(blockEntityPalette, change.newValue().blockEntityTag());
            }

            output.writeInt(statePalette.size());
            for (net.minecraft.nbt.CompoundTag tag : statePalette.keySet()) {
                StorageIo.writeCompound(output, tag);
            }
            output.writeInt(blockEntityPalette.size());
            for (net.minecraft.nbt.CompoundTag tag : blockEntityPalette.keySet()) {
                StorageIo.writeCompound(output, tag);
            }

            for (StoredBlockChange change : changes) {
                output.writeInt(packLocalPosition(change.pos()));
                output.writeInt(statePalette.get(change.oldValue().stateTag()));
                output.writeInt(statePalette.get(change.newValue().stateTag()));
                output.writeInt(blockEntityPaletteId(blockEntityPalette, change.oldValue().blockEntityTag()));
                output.writeInt(blockEntityPaletteId(blockEntityPalette, change.newValue().blockEntityTag()));
            }

            output.writeInt(entityChanges.size());
            for (StoredEntityChange change : entityChanges) {
                output.writeUTF(change.entityId());
                output.writeUTF(change.entityType());
                StorageIo.writeNullableCompound(output, change.oldValue() == null ? null : change.oldValue().copyTag());
                StorageIo.writeNullableCompound(output, change.newValue() == null ? null : change.newValue().copyTag());
            }
        }
        return chunkBuffer.toByteArray();
    }

    private void writeChunkAddressablePayload(
            OutputStream output,
            List<ChunkFrame> frames,
            List<PatchChunkSlice> slices
    ) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(output))) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeInt(frames.size());
            long offset = 12L;
            for (ChunkFrame frame : frames) {
                slices.add(new PatchChunkSlice(
                        frame.chunkX(),
                        frame.chunkZ(),
                        frame.changeCount(),
                        offset,
                        frame.frameLength()
                ));
                data.writeInt(frame.chunkX());
                data.writeInt(frame.chunkZ());
                data.writeInt(frame.uncompressedLength());
                data.writeInt(frame.compressedBytes().length);
                data.write(frame.compressedBytes());
                offset += frame.frameLength();
            }
        }
    }

    private boolean isChunkAddressablePayload(Path dataFile) throws IOException {
        if (!Files.exists(dataFile) || Files.size(dataFile) < 8L) {
            return false;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            int magic = input.readInt();
            int version = input.readInt();
            return magic == MAGIC && version == VERSION;
        }
    }

    private void readChunkAddressableHeader(DataInputStream input, Path dataFile) throws IOException {
        int magic = input.readInt();
        int version = input.readInt();
        if (magic != MAGIC || version != VERSION) {
            throw new IOException("Unsupported patch payload format for " + dataFile.getFileName());
        }
    }

    private PatchWorldChanges readChunkFrame(DataInputStream input) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        int uncompressedLength = input.readInt();
        int compressedLength = input.readInt();
        byte[] compressedBytes = new byte[compressedLength];
        input.readFully(compressedBytes);
        return this.readDecompressedChunkFrame(chunkX, chunkZ, uncompressedLength, compressedBytes);
    }

    private PatchWorldChanges readChunkFrame(RandomAccessFile input) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        int uncompressedLength = input.readInt();
        int compressedLength = input.readInt();
        byte[] compressedBytes = new byte[compressedLength];
        input.readFully(compressedBytes);
        return this.readDecompressedChunkFrame(chunkX, chunkZ, uncompressedLength, compressedBytes);
    }

    private PatchWorldChanges readDecompressedChunkFrame(
            int expectedChunkX,
            int expectedChunkZ,
            int expectedLength,
            byte[] compressedBytes
    ) throws IOException {
        byte[] chunkBytes = this.decompressFrame(compressedBytes, expectedLength);
        try (DataInputStream chunkInput = new DataInputStream(new ByteArrayInputStream(chunkBytes))) {
            PatchWorldChanges changes = this.readChunk(chunkInput, VERSION);
            for (StoredBlockChange change : changes.blockChanges()) {
                if ((change.pos().x() >> 4) != expectedChunkX || (change.pos().z() >> 4) != expectedChunkZ) {
                    throw new IOException("Patch chunk frame coordinate mismatch");
                }
            }
            return changes;
        }
    }

    private PatchWorldChanges readChunk(DataInputStream input, int version) throws IOException {
        int chunkX = input.readInt();
        int chunkZ = input.readInt();
        int changeCount = input.readInt();

        List<net.minecraft.nbt.CompoundTag> statePalette = new ArrayList<>();
        int statePaletteCount = input.readInt();
        for (int index = 0; index < statePaletteCount; index++) {
            statePalette.add(StorageIo.readCompound(input));
        }

        List<net.minecraft.nbt.CompoundTag> blockEntityPalette = new ArrayList<>();
        int blockEntityPaletteCount = input.readInt();
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
                    new StatePayload(statePalette.get(oldStateId).copy(), blockEntityAt(blockEntityPalette, oldBlockEntityId)),
                    new StatePayload(statePalette.get(newStateId).copy(), blockEntityAt(blockEntityPalette, newBlockEntityId))
            ));
        }
        if (version >= 4) {
            if (version == 4) {
                this.skipEntityLists(input);
            } else {
                return new PatchWorldChanges(changes, this.readEntityChanges(input));
            }
        }
        return new PatchWorldChanges(changes, List.of());
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
        int entityChangeCount = input.readInt();
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

    private byte[] compressFrame(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream compressed = new LZ4FrameOutputStream(output)) {
            compressed.write(bytes);
        }
        return output.toByteArray();
    }

    private byte[] decompressFrame(byte[] bytes, int expectedLength) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(0, expectedLength));
        try (LZ4FrameInputStream input = new LZ4FrameInputStream(new ByteArrayInputStream(bytes))) {
            input.transferTo(output);
        }
        byte[] decompressed = output.toByteArray();
        if (expectedLength >= 0 && decompressed.length != expectedLength) {
            throw new IOException("Patch chunk frame length mismatch");
        }
        return decompressed;
    }

    private int paletteId(LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> palette, net.minecraft.nbt.CompoundTag tag) {
        if (tag == null) {
            return -1;
        }
        return palette.computeIfAbsent(tag.copy(), ignored -> palette.size());
    }

    private int blockEntityPaletteId(LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> palette, net.minecraft.nbt.CompoundTag tag) {
        return tag == null ? -1 : palette.get(tag);
    }

    private net.minecraft.nbt.CompoundTag blockEntityAt(List<net.minecraft.nbt.CompoundTag> palette, int id) {
        return id < 0 ? null : palette.get(id).copy();
    }

    private static String chunkKey(StoredBlockChange change) {
        return (change.pos().x() >> 4) + ":" + (change.pos().z() >> 4);
    }

    private static String chunkKey(StoredEntityChange change) {
        ChunkPoint chunk = change.chunk();
        return chunk.x() + ":" + chunk.z();
    }

    private static int packLocalPosition(BlockPoint pos) {
        int normalizedY = pos.y() - Short.MIN_VALUE;
        return (normalizedY << 8) | ((pos.z() & 15) << 4) | (pos.x() & 15);
    }

    private static BlockPoint unpackPosition(int chunkX, int chunkZ, int packed) {
        int normalizedY = packed >>> 8;
        int y = normalizedY + Short.MIN_VALUE;
        int localX = packed & 15;
        int localZ = (packed >>> 4) & 15;
        return new BlockPoint((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
    }

    private static final class ChunkPayload {

        private final List<StoredBlockChange> blockChanges = new ArrayList<>();
        private final List<StoredEntityChange> entityChanges = new ArrayList<>();
    }

    private record ChunkFrame(
            int chunkX,
            int chunkZ,
            int changeCount,
            int uncompressedLength,
            byte[] compressedBytes
    ) {

        private int frameLength() {
            return 16 + this.compressedBytes.length;
        }
    }
}
