package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
}
