package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactReplayStateQueueTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recordKeepsLatestExactPlacementPerBlock() {
        ExactReplayStateQueue queue = new ExactReplayStateQueue();
        BlockPos pos = new BlockPos(1, 64, 1);

        queue.record(batch(new PreparedBlockPlacement(pos, Blocks.REDSTONE_WIRE.defaultBlockState(), null)));
        queue.record(batch(new PreparedBlockPlacement(pos, Blocks.STONE.defaultBlockState(), null)));

        assertEquals(1, queue.pendingCount());
        List<PreparedBlockPlacement> recorded = queue.takeRecordedPlacements();
        assertEquals(1, recorded.size());
        assertEquals(Blocks.STONE.defaultBlockState(), recorded.getFirst().state());
    }

    @Test
    void guardTracksOnlyVolatileRedstoneLikeStates() {
        ExactReplayStateGuard guard = new ExactReplayStateGuard();
        BlockPos pos = new BlockPos(1, 64, 1);

        assertTrue(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.REDSTONE_WIRE.defaultBlockState(),
                null
        )));
        assertTrue(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.TARGET.defaultBlockState(),
                null
        )));
        assertFalse(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.LEVER.defaultBlockState().setValue(LeverBlock.POWERED, true),
                null
        )));
        assertFalse(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.POWERED, true),
                null
        )));
        assertFalse(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.STONE.defaultBlockState(),
                null
        )));
    }

    @Test
    void guardDoesNotHoldPistonMechanismParticipants() {
        ExactReplayStateGuard guard = new ExactReplayStateGuard();
        BlockPos pos = new BlockPos(1, 64, 1);

        assertFalse(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.PISTON.defaultBlockState(),
                null
        )));
        assertFalse(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.STICKY_PISTON.defaultBlockState(),
                null
        )));
        assertFalse(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.PISTON_HEAD.defaultBlockState(),
                null
        )));
        assertFalse(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.MOVING_PISTON.defaultBlockState(),
                null
        )));
        assertFalse(guard.shouldGuard(new PreparedBlockPlacement(
                pos,
                Blocks.OBSERVER.defaultBlockState(),
                null
        )));
    }

    @Test
    void callbackSuppressionCoversPistonObserverMechanismEnvelope() {
        ExactReplayStateGuard guard = new ExactReplayStateGuard();
        BlockPos pos = new BlockPos(1, 64, 1);

        List<BlockPos> observerPositions = guard.callbackSuppressionPositions(new PreparedBlockPlacement(
                pos,
                Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, net.minecraft.core.Direction.EAST),
                null
        ));
        List<BlockPos> pistonPositions = guard.callbackSuppressionPositions(new PreparedBlockPlacement(
                pos,
                Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, net.minecraft.core.Direction.UP),
                null
        ));

        assertTrue(observerPositions.contains(pos));
        assertTrue(observerPositions.contains(pos.east()));
        assertTrue(pistonPositions.contains(pos));
        assertTrue(pistonPositions.contains(pos.above()));
    }

    @Test
    void replaySuppressionTreatsBlankCallbackSourceAsReplayFallout() {
        WorldReplayTickSuppression suppression = WorldReplayTickSuppression.getInstance();

        assertTrue(suppression.isReplayCallbackSource(null));
        assertTrue(suppression.isReplayCallbackSource(io.github.luma.domain.model.WorldMutationSource.SYSTEM));
        assertFalse(suppression.isReplayCallbackSource(io.github.luma.domain.model.WorldMutationSource.PLAYER));
    }

    @Test
    void guardReleasesForNewExplicitBuilderMutationsOnly() {
        ExactReplayStateGuard guard = new ExactReplayStateGuard();

        assertTrue(guard.isExplicitBuilderSource(io.github.luma.domain.model.WorldMutationSource.PLAYER));
        assertTrue(guard.isExplicitBuilderSource(io.github.luma.domain.model.WorldMutationSource.AXIOM));
        assertFalse(guard.isExplicitBuilderSource(io.github.luma.domain.model.WorldMutationSource.BLOCK_UPDATE));
        assertFalse(guard.isExplicitBuilderSource(io.github.luma.domain.model.WorldMutationSource.RESTORE));
    }

    private static ChunkBatch batch(PreparedBlockPlacement placement) {
        return new ChunkBatch(
                new ChunkPoint(0, 0),
                Map.of(4, new SectionBatch(4, new BitSet(4096), List.of(placement))),
                Map.of(),
                EntityBatch.empty(),
                BatchState.COMPLETE
        );
    }
}
