package io.github.luma.domain.model;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoredChangeAccumulatorTest {

    @Test
    void keepsFirstOldAndLatestNewForSameBlockPoint() {
        StoredChangeAccumulator accumulator = new StoredChangeAccumulator();
        BlockPoint point = new BlockPoint(1, 64, 1);

        accumulator.addBlockChange(new StoredBlockChange(
                point,
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        ));
        accumulator.addBlockChange(new StoredBlockChange(
                point,
                payload("minecraft:dirt"),
                payload("minecraft:gold_block")
        ));

        var changes = accumulator.blockChanges();

        assertEquals(1, changes.size());
        assertEquals("minecraft:stone", changes.getFirst().oldValue().blockId());
        assertEquals("minecraft:gold_block", changes.getFirst().newValue().blockId());
    }

    @Test
    void removesCollapsedNoOpForSameBlockPoint() {
        StoredChangeAccumulator accumulator = new StoredChangeAccumulator();
        BlockPoint point = new BlockPoint(1, 64, 1);

        accumulator.addBlockChange(new StoredBlockChange(
                point,
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        ));
        accumulator.addBlockChange(new StoredBlockChange(
                point,
                payload("minecraft:dirt"),
                payload("minecraft:stone")
        ));

        assertTrue(accumulator.blockChanges().isEmpty());
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
