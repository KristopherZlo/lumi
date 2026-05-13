package io.github.luma.storage.repository;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchEntityChunkIndex;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchSectionFrame;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.SectionFingerprint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.minecraft.world.SectionChangeMask;
import io.github.luma.storage.ProjectLayout;
import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
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

public final class PatchDataRepository {

    private static final int MAGIC = 0x4C504154;
    private static final int VERSION = 9;
    private static final int HIDDEN_MASK_V8 = 8;
    private static final int CHUNK_ADDRESSABLE_V6 = 6;
    private static final int SECTION_FRAME_V7 = 7;
    private final PatchFrameCompression frameCompression = new PatchFrameCompression();
    private final PatchEntityChunkIndexLookup entityIndexLookup = new PatchEntityChunkIndexLookup();
    private final PatchPayloadMetadataBuilder metadataBuilder = new PatchPayloadMetadataBuilder();

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
            frames.add(new ChunkFrame(
                    chunkX,
                    chunkZ,
                    chunkChanges.size(),
                    chunkBytes.length,
                    this.frameCompression.compress(chunkBytes),
                    this.metadataBuilder.sectionFingerprints(chunkX, chunkZ, chunkChanges),
                    this.metadataBuilder.visibleChangeCount(chunkChanges),
                    this.metadataBuilder.visibleSectionFingerprints(chunkX, chunkZ, chunkChanges),
                    chunkEntityChanges.size()
            ));
            chunkIndex += 1;
            BackgroundThrottle.pauseEvery(chunkIndex, 8, 250_000L);
        }

        List<PatchChunkSlice> slices = new ArrayList<>();
        StorageIo.writeAtomically(layout.patchDataFile(patchId), output -> this.writeChunkAddressablePayload(output, frames, slices));
        List<PatchEntityChunkIndex> entityChunkIndex = this.entityIndexLookup.build(entityChanges);
        return new PatchMetadata(
                patchId,
                projectId,
                versionId,
                layout.patchDataFile(patchId).getFileName().toString(),
                List.copyOf(slices),
                new PatchStats(changes.size(), slices.size()),
                entityChunkIndex
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

    public PatchSectionWorldChanges loadSectionWorldChanges(ProjectLayout layout, PatchMetadata metadata) throws IOException {
        if (metadata == null) {
            return new PatchSectionWorldChanges(List.of(), List.of());
        }
        Path dataFile = layout.patchDataFile(metadata.id());
        if (!this.isChunkAddressablePayload(dataFile)) {
            return this.toSectionWorldChanges(this.loadWorldChanges(layout, metadata));
        }

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            int version = this.readChunkAddressableHeader(input, dataFile);
            if (version != VERSION) {
                return this.toSectionWorldChanges(this.loadChunkAddressableWorldChanges(dataFile));
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

    public PatchSectionWorldChanges loadSectionWorldChanges(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<SectionFingerprint> sections
    ) throws IOException {
        if (metadata == null || sections == null || sections.isEmpty()) {
            return new PatchSectionWorldChanges(List.of(), List.of());
        }
        Set<String> requestedSections = new HashSet<>();
        Set<ChunkPoint> requestedChunks = new HashSet<>();
        for (SectionFingerprint section : sections) {
            if (section == null) {
                continue;
            }
            requestedSections.add(sectionKey(section.chunkX(), section.chunkZ(), section.sectionY()));
            requestedChunks.add(section.chunk());
        }
        if (requestedSections.isEmpty()) {
            return new PatchSectionWorldChanges(List.of(), List.of());
        }
        PatchSectionWorldChanges selectedChunks = this.toSectionWorldChanges(
                this.loadWorldChanges(layout, metadata, requestedChunks)
        );
        return new PatchSectionWorldChanges(
                selectedChunks.sectionFrames().stream()
                        .filter(frame -> requestedSections.contains(sectionKey(frame.chunkX(), frame.chunkZ(), frame.sectionY())))
                        .toList(),
                selectedChunks.entityChanges()
        );
    }

    public PatchWorldChanges loadWorldChangesForSections(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<SectionFingerprint> sections
    ) throws IOException {
        PatchSectionWorldChanges sectionChanges = this.loadSectionWorldChanges(layout, metadata, sections);
        List<StoredBlockChange> changes = new ArrayList<>();
        for (PatchSectionFrame frame : sectionChanges.sectionFrames()) {
            changes.addAll(this.toStoredChanges(frame));
        }
        return new PatchWorldChanges(changes, sectionChanges.entityChanges());
    }

    public List<StoredEntityChange> loadEntityChanges(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<String> entityIds
    ) throws IOException {
        Set<String> requestedIds = new HashSet<>();
        for (String entityId : entityIds == null ? List.<String>of() : entityIds) {
            if (entityId != null && !entityId.isBlank()) {
                requestedIds.add(entityId);
            }
        }
        if (metadata == null || requestedIds.isEmpty()) {
            return List.of();
        }
        Set<ChunkPoint> frameChunks = this.entityIndexLookup.frameChunksForEntityIds(metadata, requestedIds);
        if (!metadata.entityChunkIndex().isEmpty() && frameChunks.isEmpty()) {
            return List.of();
        }
        List<StoredEntityChange> entityChanges = frameChunks.isEmpty()
                ? this.loadWorldChanges(layout, metadata).entityChanges()
                : this.loadWorldChanges(layout, metadata, frameChunks).entityChanges();
        return entityChanges.stream()
                .filter(change -> requestedIds.contains(change.entityId()))
                .toList();
    }

    public List<StoredEntityChange> loadEntityChangesForChunks(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<ChunkPoint> chunks
    ) throws IOException {
        Set<ChunkPoint> requestedChunks = new HashSet<>(chunks == null ? List.<ChunkPoint>of() : chunks);
        if (metadata == null || requestedChunks.isEmpty()) {
            return List.of();
        }
        Set<ChunkPoint> frameChunks = this.entityIndexLookup.frameChunksForEntityChunks(metadata, requestedChunks);
        if (!metadata.entityChunkIndex().isEmpty() && frameChunks.isEmpty()) {
            return List.of();
        }
        List<StoredEntityChange> entityChanges = frameChunks.isEmpty()
                ? this.loadWorldChanges(layout, metadata).entityChanges()
                : this.loadWorldChanges(layout, metadata, frameChunks).entityChanges();
        return entityChanges.stream()
                .filter(change -> this.entityIndexLookup.touchesAnyChunk(change, requestedChunks))
                .toList();
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
            this.writeSectionFrames(output, changes);
            this.writeEntityChanges(output, entityChanges);
        }
        return chunkBuffer.toByteArray();
    }

    private void writeSectionFrames(DataOutputStream output, List<StoredBlockChange> changes) throws IOException {
        Map<Integer, List<StoredBlockChange>> bySection = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            bySection.computeIfAbsent(Math.floorDiv(change.pos().y(), 16), ignored -> new ArrayList<>()).add(change);
        }

        output.writeInt(bySection.size());
        for (Map.Entry<Integer, List<StoredBlockChange>> entry : bySection.entrySet()) {
            List<StoredBlockChange> sectionChanges = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(change -> sectionLocalIndex(change.pos())))
                    .toList();
            output.writeInt(entry.getKey());
            long[] mask = this.sectionMask(sectionChanges);
            for (long word : mask) {
                output.writeLong(word);
            }

            LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> oldStatePalette = new LinkedHashMap<>();
            LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> newStatePalette = new LinkedHashMap<>();
            LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> oldBlockEntityPalette = new LinkedHashMap<>();
            LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> newBlockEntityPalette = new LinkedHashMap<>();
            for (StoredBlockChange change : sectionChanges) {
                this.paletteId(oldStatePalette, change.oldValue().stateTag());
                this.paletteId(newStatePalette, change.newValue().stateTag());
                this.paletteId(oldBlockEntityPalette, change.oldValue().blockEntityTag());
                this.paletteId(newBlockEntityPalette, change.newValue().blockEntityTag());
            }

            this.writePalette(output, oldStatePalette);
            this.writePalette(output, newStatePalette);
            this.writePalette(output, oldBlockEntityPalette);
            this.writePalette(output, newBlockEntityPalette);
            for (StoredBlockChange change : sectionChanges) {
                output.writeInt(oldStatePalette.get(change.oldValue().stateTag()));
                output.writeInt(newStatePalette.get(change.newValue().stateTag()));
                output.writeInt(blockEntityPaletteId(oldBlockEntityPalette, change.oldValue().blockEntityTag()));
                output.writeInt(blockEntityPaletteId(newBlockEntityPalette, change.newValue().blockEntityTag()));
            }
            for (long word : this.hiddenMask(sectionChanges)) {
                output.writeLong(word);
            }
        }
    }

    private void writePalette(
            DataOutputStream output,
            LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> palette
    ) throws IOException {
        output.writeInt(palette.size());
        for (net.minecraft.nbt.CompoundTag tag : palette.keySet()) {
            StorageIo.writeCompound(output, tag);
        }
    }

    private void writeEntityChanges(DataOutputStream output, List<StoredEntityChange> entityChanges) throws IOException {
        output.writeInt(entityChanges.size());
        for (StoredEntityChange change : entityChanges) {
            output.writeUTF(change.entityId());
            output.writeUTF(change.entityType());
            StorageIo.writeNullableCompound(output, change.oldValue() == null ? null : change.oldValue().copyTag());
            StorageIo.writeNullableCompound(output, change.newValue() == null ? null : change.newValue().copyTag());
        }
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
                byte[] frameHeader = this.writeFrameHeader(frame);
                slices.add(new PatchChunkSlice(
                        frame.chunkX(),
                        frame.chunkZ(),
                        frame.changeCount(),
                        offset,
                        frameHeader.length + frame.compressedBytes().length,
                        frame.sectionFingerprints(),
                        frame.visibleChangeCount(),
                        frame.visibleSectionFingerprints(),
                        true,
                        frame.entityCount()
                ));
                data.write(frameHeader);
                data.write(frame.compressedBytes());
                offset += frameHeader.length + frame.compressedBytes().length;
            }
        }
    }

    private byte[] writeFrameHeader(ChunkFrame frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(frame.chunkX());
            output.writeInt(frame.chunkZ());
            output.writeInt(frame.sectionFingerprints().size());
            for (SectionFingerprint fingerprint : frame.sectionFingerprints()) {
                output.writeInt(fingerprint.sectionY());
                output.writeInt(fingerprint.changedCount());
                output.writeLong(fingerprint.xxHash64());
                output.writeUTF(fingerprint.sha256());
            }
            output.writeInt(frame.entityCount());
            output.writeInt(frame.uncompressedLength());
            output.writeInt(frame.compressedBytes().length);
        }
        return bytes.toByteArray();
    }

    private boolean isChunkAddressablePayload(Path dataFile) throws IOException {
        if (!Files.exists(dataFile) || Files.size(dataFile) < 8L) {
            return false;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            int magic = input.readInt();
            int version = input.readInt();
            return magic == MAGIC
                    && (version == VERSION
                    || version == HIDDEN_MASK_V8
                    || version == SECTION_FRAME_V7
                    || version == CHUNK_ADDRESSABLE_V6);
        }
    }

    private int readChunkAddressableHeader(DataInputStream input, Path dataFile) throws IOException {
        int magic = input.readInt();
        int version = input.readInt();
        if (magic != MAGIC
                || (version != VERSION
                && version != HIDDEN_MASK_V8
                && version != SECTION_FRAME_V7
                && version != CHUNK_ADDRESSABLE_V6)) {
            throw new IOException("Unsupported patch payload format for " + dataFile.getFileName());
        }
        return version;
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

    private PatchWorldChanges readChunkFrame(RandomAccessFile input, int version) throws IOException {
        return this.readChunkFrame(input, version, null);
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
        if (version >= SECTION_FRAME_V7) {
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
        if (version >= 4) {
            if (version == 4) {
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
            PatchSectionFrame frame = this.readSectionFrame(chunkX, chunkZ, input, version);
            changes.addAll(this.toStoredChanges(frame));
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
                frames.add(this.readSectionFrame(chunkX, chunkZ, chunkInput, version));
            }
            return new PatchSectionWorldChanges(frames, this.readEntityChanges(chunkInput));
        }
    }

    private void skipFrameIndex(DataInput input, int version) throws IOException {
        if (version < VERSION) {
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

    private PatchSectionFrame readSectionFrame(int chunkX, int chunkZ, DataInputStream input, int version) throws IOException {
        int sectionY = input.readInt();
        long[] mask = new long[SectionChangeMask.WORD_COUNT];
        for (int index = 0; index < mask.length; index++) {
            mask[index] = input.readLong();
        }
        List<net.minecraft.nbt.CompoundTag> oldStatePalette = this.readPalette(input);
        List<net.minecraft.nbt.CompoundTag> newStatePalette = this.readPalette(input);
        List<net.minecraft.nbt.CompoundTag> oldBlockEntityPalette = this.readPalette(input);
        List<net.minecraft.nbt.CompoundTag> newBlockEntityPalette = this.readPalette(input);
        int changedCount = new SectionChangeMask(mask).cardinality();
        int[] oldStateIds = new int[changedCount];
        int[] newStateIds = new int[changedCount];
        int[] oldBlockEntityIds = new int[changedCount];
        int[] newBlockEntityIds = new int[changedCount];
        for (int index = 0; index < changedCount; index++) {
            oldStateIds[index] = input.readInt();
            newStateIds[index] = input.readInt();
            oldBlockEntityIds[index] = input.readInt();
            newBlockEntityIds[index] = input.readInt();
        }
        long[] hiddenMask = new long[SectionChangeMask.WORD_COUNT];
        if (version >= HIDDEN_MASK_V8) {
            for (int index = 0; index < hiddenMask.length; index++) {
                hiddenMask[index] = input.readLong();
            }
        }
        return new PatchSectionFrame(
                chunkX,
                chunkZ,
                sectionY,
                mask,
                oldStatePalette,
                newStatePalette,
                oldStateIds,
                newStateIds,
                oldBlockEntityPalette,
                newBlockEntityPalette,
                oldBlockEntityIds,
                newBlockEntityIds,
                hiddenMask
        );
    }

    private List<net.minecraft.nbt.CompoundTag> readPalette(DataInputStream input) throws IOException {
        int count = StorageLimits.requireLength(
                "patch palette count",
                input.readInt(),
                StorageLimits.MAX_PALETTE_ENTRIES
        );
        List<net.minecraft.nbt.CompoundTag> palette = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            palette.add(StorageIo.readCompound(input));
        }
        return palette;
    }

    private List<StoredBlockChange> toStoredChanges(PatchSectionFrame frame) throws IOException {
        List<Integer> localIndexes = new ArrayList<>();
        new SectionChangeMask(frame.changedMask()).forEachSetCell(localIndexes::add);
        int[] oldStateIds = frame.oldStateIds();
        int[] newStateIds = frame.newStateIds();
        int[] oldBlockEntityIds = frame.oldBlockEntityIds();
        int[] newBlockEntityIds = frame.newBlockEntityIds();
        long[] hiddenMask = frame.hiddenMask();
        this.requireArrayLength("old state ids", oldStateIds, localIndexes.size());
        this.requireArrayLength("new state ids", newStateIds, localIndexes.size());
        this.requireArrayLength("old block entity ids", oldBlockEntityIds, localIndexes.size());
        this.requireArrayLength("new block entity ids", newBlockEntityIds, localIndexes.size());
        List<StoredBlockChange> changes = new ArrayList<>(localIndexes.size());
        for (int index = 0; index < localIndexes.size(); index++) {
            int localIndex = localIndexes.get(index);
            BlockPoint pos = new BlockPoint(
                    (frame.chunkX() << 4) + SectionChangeMask.localX(localIndex),
                    (frame.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                    (frame.chunkZ() << 4) + SectionChangeMask.localZ(localIndex)
            );
            changes.add(new StoredBlockChange(
                    pos,
                    new StatePayload(
                            stateAt("old state palette", frame.oldStatePalette(), oldStateIds[index]).copy(),
                            blockEntityAt("old block entity palette", frame.oldBlockEntityPalette(), oldBlockEntityIds[index])
                    ),
                    new StatePayload(
                            stateAt("new state palette", frame.newStatePalette(), newStateIds[index]).copy(),
                            blockEntityAt("new block entity palette", frame.newBlockEntityPalette(), newBlockEntityIds[index])
                    ),
                    isSet(hiddenMask, localIndex)
            ));
        }
        return changes;
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

    private int paletteId(LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> palette, net.minecraft.nbt.CompoundTag tag) {
        if (tag == null) {
            return -1;
        }
        return palette.computeIfAbsent(tag.copy(), ignored -> palette.size());
    }

    private int blockEntityPaletteId(LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> palette, net.minecraft.nbt.CompoundTag tag) {
        return tag == null ? -1 : palette.get(tag);
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

    private void requireArrayLength(String label, int[] values, int expectedLength) throws IOException {
        if (values.length != expectedLength) {
            throw new IOException("Patch section " + label + " length mismatch");
        }
    }

    private PatchSectionWorldChanges toSectionWorldChanges(PatchWorldChanges worldChanges) {
        Map<String, List<StoredBlockChange>> grouped = new LinkedHashMap<>();
        for (StoredBlockChange change : worldChanges.blockChanges()) {
            String key = chunkKey(change) + ":" + Math.floorDiv(change.pos().y(), 16);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(change);
        }
        List<PatchSectionFrame> frames = new ArrayList<>();
        for (List<StoredBlockChange> changes : grouped.values()) {
            List<StoredBlockChange> sorted = changes.stream()
                    .sorted(Comparator.comparingInt(change -> sectionLocalIndex(change.pos())))
                    .toList();
            StoredBlockChange first = sorted.getFirst();
            frames.add(this.toSectionFrame(
                    first.pos().x() >> 4,
                    first.pos().z() >> 4,
                    Math.floorDiv(first.pos().y(), 16),
                    sorted
            ));
        }
        return new PatchSectionWorldChanges(frames, worldChanges.entityChanges());
    }

    private PatchSectionFrame toSectionFrame(
            int chunkX,
            int chunkZ,
            int sectionY,
            List<StoredBlockChange> sectionChanges
    ) {
        LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> oldStatePalette = new LinkedHashMap<>();
        LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> newStatePalette = new LinkedHashMap<>();
        LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> oldBlockEntityPalette = new LinkedHashMap<>();
        LinkedHashMap<net.minecraft.nbt.CompoundTag, Integer> newBlockEntityPalette = new LinkedHashMap<>();
        int[] oldStateIds = new int[sectionChanges.size()];
        int[] newStateIds = new int[sectionChanges.size()];
        int[] oldBlockEntityIds = new int[sectionChanges.size()];
        int[] newBlockEntityIds = new int[sectionChanges.size()];
        for (int index = 0; index < sectionChanges.size(); index++) {
            StoredBlockChange change = sectionChanges.get(index);
            oldStateIds[index] = this.paletteId(oldStatePalette, change.oldValue().stateTag());
            newStateIds[index] = this.paletteId(newStatePalette, change.newValue().stateTag());
            this.paletteId(oldBlockEntityPalette, change.oldValue().blockEntityTag());
            this.paletteId(newBlockEntityPalette, change.newValue().blockEntityTag());
            oldBlockEntityIds[index] = blockEntityPaletteId(oldBlockEntityPalette, change.oldValue().blockEntityTag());
            newBlockEntityIds[index] = blockEntityPaletteId(newBlockEntityPalette, change.newValue().blockEntityTag());
        }
        return new PatchSectionFrame(
                chunkX,
                chunkZ,
                sectionY,
                this.sectionMask(sectionChanges),
                new ArrayList<>(oldStatePalette.keySet()),
                new ArrayList<>(newStatePalette.keySet()),
                oldStateIds,
                newStateIds,
                new ArrayList<>(oldBlockEntityPalette.keySet()),
                new ArrayList<>(newBlockEntityPalette.keySet()),
                oldBlockEntityIds,
                newBlockEntityIds,
                this.hiddenMask(sectionChanges)
        );
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

    private long[] sectionMask(List<StoredBlockChange> sectionChanges) {
        SectionChangeMask.Builder builder = SectionChangeMask.builder();
        for (StoredBlockChange change : sectionChanges) {
            builder.set(sectionLocalIndex(change.pos()));
        }
        return builder.build().words();
    }

    private long[] hiddenMask(List<StoredBlockChange> sectionChanges) {
        SectionChangeMask.Builder builder = SectionChangeMask.builder();
        for (StoredBlockChange change : sectionChanges) {
            if (change.hidden()) {
                builder.set(sectionLocalIndex(change.pos()));
            }
        }
        return builder.build().words();
    }

    private static boolean isSet(long[] mask, int localIndex) {
        if (mask == null || localIndex < 0) {
            return false;
        }
        int wordIndex = localIndex >>> 6;
        if (wordIndex >= mask.length) {
            return false;
        }
        return (mask[wordIndex] & (1L << (localIndex & 63))) != 0L;
    }

    private static int sectionLocalIndex(BlockPoint pos) {
        return SectionChangeMask.localIndex(pos.x() & 15, pos.y() & 15, pos.z() & 15);
    }

    private static String sectionKey(int chunkX, int chunkZ, int sectionY) {
        return chunkX + ":" + chunkZ + ":" + sectionY;
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
            byte[] compressedBytes,
            List<SectionFingerprint> sectionFingerprints,
            int visibleChangeCount,
            List<SectionFingerprint> visibleSectionFingerprints,
            int entityCount
    ) {

        private ChunkFrame {
            sectionFingerprints = sectionFingerprints == null ? List.of() : List.copyOf(sectionFingerprints);
            visibleSectionFingerprints = visibleSectionFingerprints == null
                    ? List.of()
                    : List.copyOf(visibleSectionFingerprints);
        }
    }
}
