package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
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
                Blocks.STONE.defaultBlockState(),
                null
        )));
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
