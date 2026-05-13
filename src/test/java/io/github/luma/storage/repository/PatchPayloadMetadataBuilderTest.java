package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PatchPayloadMetadataBuilderTest {

    private final PatchPayloadMetadataBuilder builder = new PatchPayloadMetadataBuilder();

    @Test
    void visibleSectionFingerprintsIgnoreHiddenChanges() throws Exception {
        List<StoredBlockChange> changes = List.of(
                change(1, 64, false, "minecraft:stone", "minecraft:gold_block"),
                change(2, 80, true, "minecraft:stone", "minecraft:diamond_block")
        );

        var full = this.builder.sectionFingerprints(0, 0, changes);
        var visible = this.builder.visibleSectionFingerprints(0, 0, changes);

        assertEquals(2, full.size());
        assertEquals(1, visible.size());
        assertEquals(4, visible.getFirst().sectionY());
        assertEquals(1, this.builder.visibleChangeCount(changes));
        assertNotEquals(full, visible);
    }

    private static StoredBlockChange change(int x, int y, boolean hidden, String oldBlock, String newBlock) {
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
