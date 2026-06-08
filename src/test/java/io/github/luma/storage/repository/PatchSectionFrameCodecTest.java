package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchSectionFrame;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchSectionFrameCodecTest {

    private final PatchSectionFrameCodec codec = new PatchSectionFrameCodec();

    @Test
    void convertsSectionFramesWithoutLosingHiddenVisibility() throws Exception {
        List<StoredBlockChange> changes = List.of(
                change(1, 64, "minecraft:stone", "minecraft:gold_block", false),
                change(2, 64, "minecraft:dirt", "minecraft:diamond_block", true)
        );

        PatchSectionFrame frame = this.codec.toSectionFrame(0, 0, 4, changes);
        List<StoredBlockChange> restored = this.codec.toStoredChanges(frame);

        assertEquals(changes, restored);
        assertTrue(restored.get(1).hidden());
    }

    @Test
    void writesAndReadsSectionFramesWithHiddenMask() throws Exception {
        List<StoredBlockChange> changes = List.of(
                change(1, 64, "minecraft:stone", "minecraft:gold_block", false),
                change(2, 80, "minecraft:dirt", "minecraft:diamond_block", true)
        );

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            this.codec.writeSectionFrames(output, changes);
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            int sectionCount = input.readInt();
            PatchSectionFrame first = this.codec.readSectionFrame(0, 0, input);
            PatchSectionFrame second = this.codec.readSectionFrame(0, 0, input);

            assertEquals(2, sectionCount);
            assertEquals(4, first.sectionY());
            assertEquals(5, second.sectionY());
            assertEquals(List.of(changes.get(0)), this.codec.toStoredChanges(first));
            assertEquals(List.of(changes.get(1)), this.codec.toStoredChanges(second));
        }
    }

    @Test
    void groupsStoredPointChangesIntoSectionFrames() throws Exception {
        List<StoredBlockChange> changes = List.of(
                change(1, 64, "minecraft:stone", "minecraft:gold_block", false),
                change(2, 80, "minecraft:dirt", "minecraft:diamond_block", true)
        );

        PatchSectionWorldChanges sectionChanges = this.codec.toSectionWorldChanges(
                new PatchWorldChanges(changes, List.of())
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
