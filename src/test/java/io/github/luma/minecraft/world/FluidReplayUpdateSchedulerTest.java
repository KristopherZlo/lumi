package io.github.luma.minecraft.world;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidReplayUpdateSchedulerTest {

    private final FluidReplayUpdateScheduler scheduler = new FluidReplayUpdateScheduler();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void collectsConnectedFluidTailAroundReplayedAirCell() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos tail = source.east();
        PreparedBlockPlacement replayedAir = new PreparedBlockPlacement(
                source,
                Blocks.AIR.defaultBlockState(),
                null,
                PreparedBlockPlacement.ReplayHint.SUPPRESS_POST_REPLAY_FLUID
        );
        Map<BlockPos, FluidState> fluids = Map.of(
                tail, Blocks.WATER.defaultBlockState().getFluidState(),
                tail.east(), Blocks.WATER.defaultBlockState().getFluidState()
        );

        Set<BlockPos> positions = this.scheduler.collectFluidUpdatePositions(
                List.of(replayedAir),
                pos -> fluids.getOrDefault(pos, Blocks.AIR.defaultBlockState().getFluidState()),
                ignored -> true
        );

        assertTrue(positions.contains(tail));
        assertTrue(positions.contains(tail.east()));
        assertFalse(positions.contains(source));
    }

    @Test
    void skipsUnloadedFluidCells() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos tail = source.east();
        PreparedBlockPlacement replayedAir = new PreparedBlockPlacement(
                source,
                Blocks.AIR.defaultBlockState(),
                null,
                PreparedBlockPlacement.ReplayHint.SUPPRESS_POST_REPLAY_FLUID
        );

        Set<BlockPos> positions = this.scheduler.collectFluidUpdatePositions(
                List.of(replayedAir),
                pos -> Blocks.WATER.defaultBlockState().getFluidState(),
                pos -> !pos.equals(tail)
        );

        assertFalse(positions.contains(tail));
    }
}
