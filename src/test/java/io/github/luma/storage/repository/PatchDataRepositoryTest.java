package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.SectionFingerprint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.ProjectLayout;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchDataRepositoryTest {

    @TempDir
    Path tempDir;

    private final PatchDataRepository repository = new PatchDataRepository();

    @Test
    void roundTripsChunkIndexedPayloadWithBlockEntities() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:chest", blockEntity("minecraft:chest", 1))
                ),
                new StoredBlockChange(
                        new BlockPoint(17, 65, 2),
                        payload("minecraft:dirt", null),
                        payload("minecraft:diamond_block", null)
                )
        );

        PatchMetadata metadata = this.repository.writePayload(layout, "patch-0001", "project", "v0001", changes);
        List<StoredBlockChange> restored = this.repository.loadChanges(layout, metadata);

        assertEquals(2, metadata.stats().changedBlocks());
        assertEquals(2, metadata.stats().changedChunks());
        assertEquals(changes, restored);
        assertTrue(metadata.chunks().stream().anyMatch(slice -> slice.chunkX() == 0 && slice.chunkZ() == 0));
        assertTrue(metadata.chunks().stream().anyMatch(slice -> slice.chunkX() == 1 && slice.chunkZ() == 0));
        assertTrue(metadata.chunks().stream().allMatch(slice -> slice.dataOffsetBytes() >= 12L));
        assertTrue(metadata.chunks().stream().allMatch(slice -> slice.dataLengthBytes() > 16));
    }

    @Test
    void roundTripsHiddenBlockChangeVisibility() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:air", null),
                payload("minecraft:wheat", null),
                true
        ));

        PatchMetadata metadata = this.repository.writePayload(layout, "patch-hidden", "project", "v-hidden", changes);
        List<StoredBlockChange> restored = this.repository.loadChanges(layout, metadata);

        assertEquals(1, restored.size());
        assertTrue(restored.getFirst().hidden());
    }

    @Test
    void loadsSelectedChunksFromChunkAddressablePayload() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:gold_block", null)
                ),
                new StoredBlockChange(
                        new BlockPoint(17, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:diamond_block", null)
                )
        );

        PatchMetadata metadata = this.repository.writePayload(layout, "patch-selective", "project", "v0003", changes);
        PatchWorldChanges selected = this.repository.loadWorldChanges(
                layout,
                metadata,
                List.of(new io.github.luma.domain.model.ChunkPoint(1, 0))
        );

        assertEquals(1, selected.blockChanges().size());
        assertEquals(new BlockPoint(17, 64, 1), selected.blockChanges().getFirst().pos());
        assertFalse(selected.blockChanges().stream().anyMatch(change -> change.pos().equals(new BlockPoint(1, 64, 1))));
    }

    @Test
    void exposesSectionFramesForCurrentPayloads() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:gold_block", null)
                ),
                new StoredBlockChange(
                        new BlockPoint(2, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:diamond_block", null)
                )
        );

        PatchMetadata metadata = this.repository.writePayload(layout, "patch-section-v7", "project", "v0004", changes);
        PatchSectionWorldChanges sectionChanges = this.repository.loadSectionWorldChanges(layout, metadata);

        assertEquals(1, sectionChanges.sectionFrames().size());
        var frame = sectionChanges.sectionFrames().getFirst();
        assertEquals(0, frame.chunkX());
        assertEquals(0, frame.chunkZ());
        assertEquals(4, frame.sectionY());
        assertEquals(2, frame.oldStateIds().length);
        assertEquals(2, frame.newStateIds().length);
        assertEquals(2, java.util.Arrays.stream(frame.changedMask()).map(Long::bitCount).sum());
    }

    @Test
    void writesSectionFingerprintsIntoPatchChunkIndex() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:gold_block", null)
                ),
                new StoredBlockChange(
                        new BlockPoint(1, 80, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:diamond_block", null)
                )
        );

        PatchMetadata metadata = this.repository.writePayload(layout, "patch-fingerprint", "project", "v-fp", changes);

        assertEquals(1, metadata.chunks().size());
        assertEquals(2, metadata.chunks().getFirst().sectionFingerprints().size());
        assertTrue(metadata.chunks().getFirst().sectionFingerprints().stream()
                .allMatch(fingerprint -> fingerprint.xxHash64() != 0L && fingerprint.sha256().length() == 64));
    }

    @Test
    void writesVisibleSectionIndexForPreviewBounds() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:gold_block", null)
                ),
                new StoredBlockChange(
                        new BlockPoint(1, 80, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:wheat", null),
                        true
                )
        );

        PatchMetadata metadata = this.repository.writePayload(layout, "patch-visible-index", "project", "v-visible", changes);
        PatchChunkSlice slice = metadata.chunks().getFirst();

        assertTrue(slice.visibleSectionIndexAvailable());
        assertEquals(2, slice.changeCount());
        assertEquals(1, slice.visibleChangeCount());
        assertEquals(2, slice.sectionFingerprints().size());
        assertEquals(1, slice.visibleSectionFingerprints().size());
        assertEquals(4, slice.visibleSectionFingerprints().getFirst().sectionY());
    }

    @Test
    void filtersCurrentSectionFramesByFingerprintIndex() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:gold_block", null)
                ),
                new StoredBlockChange(
                        new BlockPoint(1, 80, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:diamond_block", null)
                )
        );

        PatchMetadata metadata = this.repository.writePayload(layout, "patch-section-filter", "project", "v-filter", changes);
        SectionFingerprint requested = metadata.chunks().getFirst().sectionFingerprints().stream()
                .filter(fingerprint -> fingerprint.sectionY() == 5)
                .findFirst()
                .orElseThrow();
        PatchSectionWorldChanges sectionChanges = this.repository.loadSectionWorldChanges(
                layout,
                metadata,
                List.of(requested)
        );

        assertEquals(1, sectionChanges.sectionFrames().size());
        assertEquals(5, sectionChanges.sectionFrames().getFirst().sectionY());

        PatchWorldChanges selectedWorldChanges = this.repository.loadWorldChangesForSections(
                layout,
                metadata,
                List.of(requested)
        );
        assertEquals(1, selectedWorldChanges.blockChanges().size());
        assertEquals(80, selectedWorldChanges.blockChanges().getFirst().pos().y());
    }

    @Test
    void roundTripsEntityChangesInChunkPayload() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        String entityId = "00000000-0000-0000-0000-000000000030";
        List<StoredEntityChange> entityChanges = List.of(new StoredEntityChange(
                entityId,
                "minecraft:block_display",
                entity("minecraft:block_display", entityId, 1.0D),
                entity("minecraft:block_display", entityId, 2.0D)
        ));

        PatchMetadata metadata = this.repository.writePayload(
                layout,
                "patch-entity",
                "project",
                "v0002",
                List.of(),
                entityChanges
        );
        PatchWorldChanges restored = this.repository.loadWorldChanges(layout, metadata);

        assertTrue(restored.blockChanges().isEmpty());
        assertEquals(1, restored.entityChanges().size());
        assertEquals(entityId, restored.entityChanges().getFirst().entityId());
        assertEquals(2.0D, restored.entityChanges().getFirst().newValue()
                .entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D));
    }

    @Test
    void indexesEntityChangesByOldAndNewChunkForSelectiveReads() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        String entityId = "00000000-0000-0000-0000-000000000032";
        StoredEntityChange movedOut = new StoredEntityChange(
                entityId,
                "minecraft:block_display",
                entity("minecraft:block_display", entityId, 1.0D),
                entity("minecraft:block_display", entityId, 32.0D)
        );

        PatchMetadata metadata = this.repository.writePayload(
                layout,
                "patch-entity-index",
                "project",
                "v0007",
                List.of(),
                List.of(movedOut)
        );

        assertEquals(1, metadata.entityChunkIndex().size());
        assertEquals(new ChunkPoint(2, 0), metadata.entityChunkIndex().getFirst().frameChunk());
        assertEquals(new ChunkPoint(0, 0), metadata.entityChunkIndex().getFirst().oldChunk());
        assertEquals(new ChunkPoint(2, 0), metadata.entityChunkIndex().getFirst().newChunk());
        assertEquals(List.of(movedOut), this.repository.loadEntityChangesForChunks(
                layout,
                metadata,
                List.of(new ChunkPoint(0, 0))
        ));
        assertEquals(List.of(movedOut), this.repository.loadEntityChanges(
                layout,
                metadata,
                List.of(entityId)
        ));
    }

    @Test
    void rejectsNegativeNbtLengthWithoutAllocation() {
        byte[] bytes = new byte[] {-1, -1, -1, -1};

        assertThrows(IOException.class, () -> StorageIo.readCompound(new DataInputStream(new ByteArrayInputStream(bytes))));
    }

    @Test
    void rejectsCorruptPatchFrameLength() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        Path dataFile = layout.patchDataFile("corrupt-frame");
        Files.createDirectories(dataFile.getParent());
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(dataFile))) {
            output.writeInt(0x4C504154);
            output.writeInt(7);
            output.writeInt(1);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(-1);
            output.writeInt(0);
        }

        assertThrows(IOException.class, () -> this.repository.loadWorldChanges(layout, metadata("corrupt-frame", List.of())));
    }

    @Test
    void rejectsInvalidPaletteIdsWithIOException() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        Path dataFile = layout.patchDataFile("bad-palette");
        byte[] frame = this.corruptPaletteFrame();
        byte[] compressedFrame = this.compressFrame(frame);
        Files.createDirectories(dataFile.getParent());
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(dataFile))) {
            output.writeInt(0x4C504154);
            output.writeInt(7);
            output.writeInt(1);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(frame.length);
            output.writeInt(compressedFrame.length);
            output.write(compressedFrame);
        }

        assertThrows(IOException.class, () -> this.repository.loadWorldChanges(layout, metadata("bad-palette", List.of())));
    }

    @Test
    void selectedChunkReadRejectsSliceOutsideFileBounds() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        PatchMetadata metadata = this.repository.writePayload(
                layout,
                "bad-slice",
                "project",
                "v0005",
                List.of(new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:gold_block", null)
                ))
        );
        PatchMetadata badMetadata = metadata(
                metadata.id(),
                List.of(new PatchChunkSlice(0, 0, 1, 999_999L, 64))
        );

        assertThrows(IOException.class, () -> this.repository.loadWorldChanges(layout, badMetadata, List.of(new ChunkPoint(0, 0))));
    }

    @Test
    void selectedChunkReadRejectsMismatchedEntityFrameChunk() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        String entityId = "00000000-0000-0000-0000-000000000031";
        PatchMetadata metadata = this.repository.writePayload(
                layout,
                "entity-mismatch",
                "project",
                "v0006",
                List.of(),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        null,
                        entity("minecraft:block_display", entityId, 32.0D)
                ))
        );
        PatchChunkSlice actual = metadata.chunks().getFirst();
        PatchMetadata badMetadata = metadata(
                metadata.id(),
                List.of(new PatchChunkSlice(0, 0, actual.changeCount(), actual.dataOffsetBytes(), actual.dataLengthBytes()))
        );

        assertThrows(IOException.class, () -> this.repository.loadWorldChanges(layout, badMetadata, List.of(new ChunkPoint(0, 0))));
    }

    private static StatePayload payload(String blockId, net.minecraft.nbt.CompoundTag blockEntity) {
        return new StatePayload(state(blockId), blockEntity);
    }

    private static CompoundTag state(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return state;
    }

    private static CompoundTag blockEntity(String id, int items) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("Items", items);
        return tag;
    }

    private static EntityPayload entity(String type, String uuid, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }

    private static PatchMetadata metadata(String patchId, List<PatchChunkSlice> chunks) {
        return new PatchMetadata(patchId, "project", "version", patchId + ".bin.lz4", chunks, new PatchStats(0, chunks.size()));
    }

    private byte[] corruptPaletteFrame() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(1);
            output.writeInt(1);
            output.writeInt(4);
            output.writeLong(1L);
            output.writeLong(0L);
            output.writeLong(0L);
            output.writeLong(0L);
            output.writeInt(1);
            StorageIo.writeCompound(output, state("minecraft:stone"));
            output.writeInt(1);
            StorageIo.writeCompound(output, state("minecraft:dirt"));
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(5);
            output.writeInt(0);
            output.writeInt(-1);
            output.writeInt(-1);
            output.writeInt(0);
        }
        return bytes.toByteArray();
    }

    private byte[] compressFrame(byte[] frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream output = new LZ4FrameOutputStream(bytes)) {
            output.write(frame);
        }
        return bytes.toByteArray();
    }
}
