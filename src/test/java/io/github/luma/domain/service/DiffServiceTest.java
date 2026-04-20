package io.github.luma.domain.service;

import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.StatePayload;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffServiceTest {

    private final DiffService diffService = new DiffService();

    @Test
    void extractBlockIdReadsStateName() {
        String blockId = this.diffService.extractBlockId("{Name:\"minecraft:stone\",Properties:{axis:\"y\"}}");

        assertEquals("minecraft:stone", blockId);
    }

    @Test
    void extractBlockIdFallsBackForBlankAndUnknown() {
        assertEquals("minecraft:air", this.diffService.extractBlockId(""));
        assertEquals("minecraft:unknown", this.diffService.extractBlockId("{foo:\"bar\"}"));
    }

    @Test
    void statesEqualUsesStructuredPayloads() {
        StatePayload left = payload("minecraft:stone");
        StatePayload right = payload("minecraft:stone");
        StatePayload changed = payload("minecraft:dirt");

        assertTrue(this.diffService.statesEqual(left, right));
        assertFalse(this.diffService.statesEqual(left, changed));
    }

    @Test
    void classifyStateChangeUsesBlockIdsWithoutSnbtRoundTrip() {
        StatePayload air = payload("minecraft:air");
        StatePayload stone = payload("minecraft:stone");
        StatePayload dirt = payload("minecraft:dirt");

        assertEquals(ChangeType.ADDED, this.diffService.classifyStateChange(air, stone));
        assertEquals(ChangeType.REMOVED, this.diffService.classifyStateChange(stone, air));
        assertEquals(ChangeType.CHANGED, this.diffService.classifyStateChange(stone, dirt));
    }

    private static StatePayload payload(String blockId) {
        CompoundTag stateTag = new CompoundTag();
        stateTag.putString("Name", blockId);
        return new StatePayload(stateTag, null);
    }
}
