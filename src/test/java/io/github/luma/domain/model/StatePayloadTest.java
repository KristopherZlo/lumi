package io.github.luma.domain.model;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatePayloadTest {

    @Test
    void copiesNbtOnInputAndOutput() {
        CompoundTag state = state("minecraft:stone");
        StatePayload payload = new StatePayload(state, null);

        state.putString("Name", "minecraft:dirt");
        payload.stateTag().putString("Name", "minecraft:gold_block");

        assertEquals("minecraft:stone", payload.blockId());
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
