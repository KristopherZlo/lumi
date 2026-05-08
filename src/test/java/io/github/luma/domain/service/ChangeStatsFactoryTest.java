package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeStatsFactoryTest {

    @Test
    void summariesIgnoreHiddenBuilderSurfaceChanges() {
        var stats = ChangeStatsFactory.summarize(List.of(
                change(1, "minecraft:stone", false),
                change(2, "minecraft:wheat", true)
        ));
        var pending = ChangeStatsFactory.summarizePending(List.of(
                change(1, "minecraft:stone", false),
                change(2, "minecraft:wheat", true)
        ));

        assertEquals(1, stats.changedBlocks());
        assertEquals(1, stats.changedChunks());
        assertEquals(1, stats.distinctBlockTypes());
        assertEquals(1, pending.addedBlocks());
    }

    private static StoredBlockChange change(int x, String blockId, boolean hidden) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 0),
                payload("minecraft:air"),
                payload(blockId),
                hidden
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }
}
