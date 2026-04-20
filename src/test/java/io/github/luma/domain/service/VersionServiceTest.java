package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionServiceTest {

    @Test
    void mergeChangesBuildsParentToCurrentDiffForAmend() {
        List<StoredBlockChange> merged = VersionService.mergeChanges(
                List.of(
                        change(1, "minecraft:stone", "minecraft:dirt"),
                        change(2, "minecraft:oak_planks", "minecraft:glass")
                ),
                List.of(
                        change(1, "minecraft:dirt", "minecraft:gold_block"),
                        change(2, "minecraft:glass", "minecraft:oak_planks")
                )
        );

        assertEquals(1, merged.size());
        assertEquals(new BlockPoint(1, 64, 1), merged.getFirst().pos());
        assertEquals("minecraft:stone", merged.getFirst().oldValue().blockId());
        assertEquals("minecraft:gold_block", merged.getFirst().newValue().blockId());
    }

    @Test
    void mergeChangesDropsFullReverts() {
        List<StoredBlockChange> merged = VersionService.mergeChanges(
                List.of(change(1, "minecraft:stone", "minecraft:dirt")),
                List.of(change(1, "minecraft:dirt", "minecraft:stone"))
        );

        assertTrue(merged.isEmpty());
    }

    private static StoredBlockChange change(int x, String leftBlockId, String rightBlockId) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, x),
                payload(leftBlockId),
                payload(rightBlockId)
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }
}
