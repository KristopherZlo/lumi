package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchPayloadReaderTest {

    @TempDir
    Path tempDir;

    private final PatchPayloadWriter writer = new PatchPayloadWriter();
    private final PatchPayloadReader reader = new PatchPayloadReader();

    @Test
    void readsFullAndSelectedChunkPayloads() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                change(1, 64, "minecraft:stone", "minecraft:gold_block", false),
                change(17, 80, "minecraft:dirt", "minecraft:diamond_block", true)
        );
        PatchMetadata metadata = this.writer.writePayload(layout, "patch-reader", "project", "version", changes, List.of());

        PatchWorldChanges full = this.reader.loadWorldChanges(layout.patchDataFile(metadata.id()), metadata);
        PatchWorldChanges selected = this.reader.loadWorldChanges(
                layout.patchDataFile(metadata.id()),
                metadata,
                List.of(new ChunkPoint(1, 0))
        );

        assertEquals(changes, full.blockChanges());
        assertEquals(List.of(changes.get(1)), selected.blockChanges());
        assertTrue(selected.blockChanges().getFirst().hidden());
    }

    @Test
    void readsCurrentPayloadSectionFrames() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                change(1, 64, "minecraft:stone", "minecraft:gold_block", false),
                change(2, 80, "minecraft:dirt", "minecraft:diamond_block", true)
        );
        PatchMetadata metadata = this.writer.writePayload(layout, "patch-reader-sections", "project", "version", changes, List.of());

        PatchSectionWorldChanges sectionChanges = this.reader.loadSectionWorldChanges(
                layout.patchDataFile(metadata.id()),
                metadata
        );

        assertEquals(2, sectionChanges.sectionFrames().size());
        assertEquals(4, sectionChanges.sectionFrames().get(0).sectionY());
        assertEquals(5, sectionChanges.sectionFrames().get(1).sectionY());
    }

    private static StoredBlockChange change(int x, int y, String oldBlock, String newBlock, boolean hidden) {
        return new StoredBlockChange(
                new BlockPoint(x, y, 1),
                payload(oldBlock),
                payload(newBlock),
                hidden
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
