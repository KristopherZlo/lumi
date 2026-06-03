package io.github.luma.minecraft.world;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
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

    @Test
    void cleanupCollectsOnlyCurrentNonSourceFluidRemovalTargets() {
        BlockPos target = new BlockPos(0, 64, 0);
        BlockPos adjacentTail = target.east();
        BlockPos sourceNeighbor = target.west();
        PreparedBlockPlacement replayedAir = new PreparedBlockPlacement(
                target,
                Blocks.AIR.defaultBlockState(),
                null,
                PreparedBlockPlacement.ReplayHint.SUPPRESS_POST_REPLAY_FLUID
        );
        Map<BlockPos, FluidState> fluids = Map.of(
                target, Fluids.FLOWING_WATER.defaultFluidState().setValue(FlowingFluid.LEVEL, 7),
                adjacentTail, Fluids.FLOWING_WATER.defaultFluidState().setValue(FlowingFluid.LEVEL, 6),
                sourceNeighbor, Fluids.WATER.defaultFluidState()
        );

        Set<BlockPos> positions = this.scheduler.collectFluidTailCleanupPositions(
                List.of(replayedAir),
                pos -> fluids.getOrDefault(pos, Blocks.AIR.defaultBlockState().getFluidState()),
                ignored -> true
        );

        assertTrue(positions.contains(target));
        assertFalse(positions.contains(adjacentTail));
        assertFalse(positions.contains(sourceNeighbor));
    }

    @Test
    void cleanupSkipsFluidPlacementTargets() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos flowingTail = source.east();
        PreparedBlockPlacement replayedWater = new PreparedBlockPlacement(
                source,
                Blocks.WATER.defaultBlockState(),
                null,
                PreparedBlockPlacement.ReplayHint.SUPPRESS_POST_REPLAY_FLUID
        );
        Map<BlockPos, FluidState> fluids = Map.of(
                flowingTail, Fluids.FLOWING_WATER.defaultFluidState().setValue(FlowingFluid.LEVEL, 7)
        );

        Set<BlockPos> positions = this.scheduler.collectFluidTailCleanupPositions(
                List.of(replayedWater),
                pos -> fluids.getOrDefault(pos, Blocks.AIR.defaultBlockState().getFluidState()),
                ignored -> true
        );

        assertTrue(positions.isEmpty());
    }

    @Test
    void cleanupDoesNotWalkIntoFluidOwnedByEarlierActions() {
        BlockPos latestTarget = new BlockPos(0, 64, 0);
        BlockPos earlierTail = latestTarget.west();
        PreparedBlockPlacement replayedDryTarget = new PreparedBlockPlacement(
                latestTarget,
                Blocks.GRASS_BLOCK.defaultBlockState(),
                null,
                PreparedBlockPlacement.ReplayHint.SUPPRESS_POST_REPLAY_FLUID
        );
        Map<BlockPos, FluidState> fluids = Map.of(
                latestTarget, Fluids.FLOWING_WATER.defaultFluidState().setValue(FlowingFluid.LEVEL, 7),
                earlierTail, Fluids.FLOWING_WATER.defaultFluidState().setValue(FlowingFluid.LEVEL, 6)
        );

        Set<BlockPos> positions = this.scheduler.collectFluidTailCleanupPositions(
                List.of(replayedDryTarget),
                pos -> fluids.getOrDefault(pos, Blocks.AIR.defaultBlockState().getFluidState()),
                ignored -> true
        );

        assertTrue(positions.contains(latestTarget));
        assertFalse(positions.contains(earlierTail));
    }
}
