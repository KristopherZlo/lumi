package io.github.luma.minecraft.world;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockChangeApplierSafetyTest {

    @Test
    void blockEntityTailExceptionCountsAsProcessedAndRecordsFailure() {
        WorldApplyMetrics metrics = new WorldApplyMetrics();
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");

        int processed = BlockChangeApplier.applyBlockEntities(
                null,
                List.of(Map.entry(new BlockPos(1, 64, 1), blockEntity)),
                0,
                1,
                metrics
        );

        assertEquals(1, processed);
        assertTrue(metrics.summary().contains("applyFailures=1"));
    }
}
