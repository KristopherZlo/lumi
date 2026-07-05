package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyNoOpPrunerTest {

    private final WorldApplyNoOpPruner pruner = new WorldApplyNoOpPruner();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void dropsNoOpExactReplayWhenNothingNearbyChanges() {
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                new BlockPos(4, 64, 4),
                Blocks.REDSTONE_WIRE.defaultBlockState(),
                null
        );

        assertFalse(this.pruner.shouldKeepNoOpReplay(placement, new LongOpenHashSet()));
    }

    @Test
    void keepsForcedReplayCompanionEvenWhenLiveStateAlreadyMatches() {
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                new BlockPos(4, 64, 4),
                Blocks.AIR.defaultBlockState(),
                null,
                PreparedBlockPlacement.ReplayHint.FORCE_FINAL_REPLAY
        );

        assertTrue(this.pruner.shouldKeepNoOpReplay(placement, new LongOpenHashSet()));
    }

    @Test
    void keepsMechanismSuppressionReplayEvenWhenLiveStateAlreadyMatches() {
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                new BlockPos(4, 64, 4),
                Blocks.AIR.defaultBlockState(),
                null,
                PreparedBlockPlacement.ReplayHint.SUPPRESS_POST_REPLAY_MECHANISM
        );

        assertTrue(this.pruner.shouldKeepNoOpReplay(placement, new LongOpenHashSet()));
    }

    @Test
    void keepsExactReplayNextToRealUpdate() {
        BlockPos pos = new BlockPos(4, 64, 4);
        LongOpenHashSet updatedPositions = new LongOpenHashSet();
        updatedPositions.add(pos.east().asLong());
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                pos,
                Blocks.REDSTONE_WIRE.defaultBlockState(),
                null
        );

        assertTrue(this.pruner.shouldKeepNoOpReplay(placement, updatedPositions));
    }

    @Test
    void keepsExactReplayOnChunkBoundaryForCrossChunkNeighbors() {
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                new BlockPos(16, 64, 4),
                Blocks.REDSTONE_WIRE.defaultBlockState(),
                null
        );

        assertTrue(this.pruner.shouldKeepNoOpReplay(placement, new LongOpenHashSet()));
    }

    @Test
    void dropsPlainNoOpPlacementEvenWhenAdjacentBlockChanges() {
        BlockPos pos = new BlockPos(4, 64, 4);
        LongOpenHashSet updatedPositions = new LongOpenHashSet();
        updatedPositions.add(pos.north().asLong());
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                pos,
                Blocks.STONE.defaultBlockState(),
                null
        );

        assertFalse(this.pruner.shouldKeepNoOpReplay(placement, updatedPositions));
    }

    @Test
    void promotesDryTargetWhenLiveCellAlreadyContainsFluid() {
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                new BlockPos(4, 64, 4),
                Blocks.STONE.defaultBlockState(),
                null
        );

        PreparedBlockPlacement promoted = this.withLiveFluidReplayHint(
                placement,
                Blocks.WATER.defaultBlockState()
        );

        assertTrue(promoted.replayHint().suppressesPostReplayFluid());
        assertTrue(this.pruner.shouldKeepNoOpReplay(promoted, new LongOpenHashSet()));
    }

    @Test
    void liveFluidPromotionKeepsExistingReplayHintBits() {
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                new BlockPos(4, 64, 4),
                Blocks.AIR.defaultBlockState(),
                null,
                PreparedBlockPlacement.ReplayHint.FORCE_FINAL_REPLAY
        );

        PreparedBlockPlacement promoted = this.withLiveFluidReplayHint(
                placement,
                Blocks.WATER.defaultBlockState()
        );

        assertTrue(promoted.replayHint().suppressesPostReplayFluid());
        assertTrue(promoted.replayHint().forcesFinalReplay());
    }

    @Test
    void mechanismReplayOverLiveWaterPromotesFluidTailCleanup() {
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                new BlockPos(4, 64, 4),
                Blocks.REDSTONE_WIRE.defaultBlockState(),
                null,
                PreparedBlockPlacement.ReplayHint.SUPPRESS_POST_REPLAY_MECHANISM
        );

        PreparedBlockPlacement promoted = this.withLiveFluidReplayHint(
                placement,
                Blocks.WATER.defaultBlockState()
        );

        assertTrue(promoted.replayHint().suppressesPostReplayFluid());
        assertTrue(promoted.replayHint().suppressesPostReplayMechanism());
    }

    @Test
    void doesNotPromoteWhenLiveCellAndTargetAreDry() {
        PreparedBlockPlacement placement = new PreparedBlockPlacement(
                new BlockPos(4, 64, 4),
                Blocks.STONE.defaultBlockState(),
                null
        );

        PreparedBlockPlacement promoted = this.withLiveFluidReplayHint(
                placement,
                Blocks.AIR.defaultBlockState()
        );

        assertEquals(PreparedBlockPlacement.ReplayHint.NONE, promoted.replayHint());
    }

    @Test
    void promotedLiveFluidPlacementSeedsOrphanedTailCleanup() {
        BlockPos restored = new BlockPos(4, 64, 4);
        BlockPos source = restored.north();
        BlockPos tail = restored.south();
        PreparedBlockPlacement promoted = this.withLiveFluidReplayHint(
                new PreparedBlockPlacement(restored, Blocks.STONE.defaultBlockState(), null),
                Blocks.WATER.defaultBlockState()
        );
        Map<BlockPos, FluidState> fluids = Map.of(
                source, Fluids.WATER.defaultFluidState(),
                restored, Fluids.FLOWING_WATER.defaultFluidState(),
                tail, Fluids.FLOWING_WATER.defaultFluidState()
        );

        Set<BlockPos> cleanup = new FluidReplayUpdateScheduler().collectFluidTailCleanupPositions(
                Set.of(promoted),
                pos -> fluids.getOrDefault(pos, Fluids.EMPTY.defaultFluidState()),
                pos -> true
        );

        assertTrue(cleanup.contains(restored));
        assertTrue(cleanup.contains(tail));
        assertFalse(cleanup.contains(source));
    }

    private PreparedBlockPlacement withLiveFluidReplayHint(
            PreparedBlockPlacement placement,
            BlockState currentState
    ) {
        return this.pruner.withLiveFluidReplayHint(placement, currentState);
    }
}
