package io.github.luma.gbreak.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

final class CorruptionRestoreWavePlannerTest {

    @Test
    void ordersEntriesFromCenterOutward() {
        CorruptionRestoreWavePlanner planner = new CorruptionRestoreWavePlanner();
        BlockPos center = new BlockPos(10, 64, 10);
        List<BlockPos> unordered = List.of(
                new BlockPos(16, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(12, 64, 10),
                new BlockPos(10, 68, 10)
        );

        List<BlockPos> ordered = planner.orderFromCenter(unordered, pos -> planner.distanceSquared(center, pos));

        assertEquals(List.of(
                new BlockPos(10, 64, 10),
                new BlockPos(12, 64, 10),
                new BlockPos(10, 68, 10),
                new BlockPos(16, 64, 10)
        ), ordered);
    }
}
