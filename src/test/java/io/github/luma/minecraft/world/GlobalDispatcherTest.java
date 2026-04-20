package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GlobalDispatcherTest {

    @Test
    void chunkBatchFromPreparedGroupsPlacementsBySectionAndCopiesBlockEntities() {
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");

        PreparedChunkBatch prepared = new PreparedChunkBatch(
                new ChunkPoint(1, 2),
                List.of(
                        new PreparedBlockPlacement(new BlockPos(16, 0, 32), null, null),
                        new PreparedBlockPlacement(new BlockPos(17, 1, 33), null, blockEntity),
                        new PreparedBlockPlacement(new BlockPos(18, 20, 34), null, null)
                )
        );

        ChunkBatch batch = ChunkBatch.fromPrepared(prepared);

        Assertions.assertEquals(new ChunkPoint(1, 2), batch.chunk());
        Assertions.assertEquals(2, batch.sections().size());
        Assertions.assertTrue(batch.sections().containsKey(0));
        Assertions.assertTrue(batch.sections().containsKey(1));
        Assertions.assertEquals(2, batch.sections().get(0).placementCount());
        Assertions.assertEquals(1, batch.sections().get(1).placementCount());
        Assertions.assertEquals(1, batch.blockEntities().size());
        Assertions.assertNotSame(blockEntity, batch.blockEntities().values().iterator().next());
    }

    @Test
    void dispatcherDrainsCompletedQueuesBeforeIncompleteFallback() {
        GlobalDispatcher dispatcher = new GlobalDispatcher();
        LocalQueue completedQueue = LocalQueue.completed(List.of(chunkBatch(0, BatchState.COMPLETE)));
        LocalQueue incompleteQueue = new LocalQueue();
        for (int index = 0; index < 64; index++) {
            incompleteQueue.offer(chunkBatch(index + 1, BatchState.INCOMPLETE));
        }

        dispatcher.enqueue(completedQueue);
        dispatcher.enqueue(incompleteQueue);

        ChunkBatch first = dispatcher.pollNext();
        ChunkBatch second = dispatcher.pollNext();

        Assertions.assertNotNull(first);
        Assertions.assertEquals(0, first.chunk().x());
        Assertions.assertNotNull(second);
        Assertions.assertEquals(1, second.chunk().x());
    }

    @Test
    void dispatcherHoldsIncompleteQueuesUntilThresholdIsReached() {
        GlobalDispatcher dispatcher = new GlobalDispatcher();
        LocalQueue incompleteQueue = new LocalQueue();
        for (int index = 0; index < 63; index++) {
            incompleteQueue.offer(chunkBatch(index, BatchState.INCOMPLETE));
        }
        dispatcher.enqueue(incompleteQueue);

        Assertions.assertNull(dispatcher.pollNext());

        incompleteQueue.offer(chunkBatch(63, BatchState.INCOMPLETE));
        ChunkBatch released = dispatcher.pollNext();
        Assertions.assertNotNull(released);
        Assertions.assertEquals(0, released.chunk().x());
    }

    private static ChunkBatch chunkBatch(int chunkX, BatchState state) {
        Map<Integer, SectionBatch> sections = new LinkedHashMap<>();
        BitSet changed = new BitSet(4096);
        changed.set(0);
        sections.put(0, new SectionBatch(
                0,
                changed,
                List.of(new PreparedBlockPlacement(new BlockPos(chunkX << 4, 64, 0), null, null))
        ));
        return new ChunkBatch(
                new ChunkPoint(chunkX, 0),
                Map.copyOf(sections),
                Map.of(),
                EntityBatch.empty(),
                state
        );
    }
}
