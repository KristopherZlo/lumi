package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldLightUpdateQueueTest {

    @Test
    void coalescesSolidLightUpdatesToSurfacePositions() {
        SectionLightUpdateBatch batch = new SectionLightUpdateBatch();
        for (int x = 0; x < 3; x++) {
            for (int y = 48; y < 51; y++) {
                for (int z = 0; z < 3; z++) {
                    batch.addSurfaceCandidate(new BlockPos(x, y, z));
                }
            }
        }
        WorldLightUpdateQueue queue = new WorldLightUpdateQueue();

        queue.add(batch);

        queue.prepareDrainPositions();

        Assertions.assertEquals(26, queue.pendingCount());
    }

    @Test
    void keepsExactEmissivePositionsAlongsideSurfacePositions() {
        SectionLightUpdateBatch batch = new SectionLightUpdateBatch();
        for (int x = 0; x < 3; x++) {
            for (int y = 48; y < 51; y++) {
                for (int z = 0; z < 3; z++) {
                    batch.addSurfaceCandidate(new BlockPos(x, y, z));
                }
            }
        }
        batch.addExact(new BlockPos(1, 49, 1));
        WorldLightUpdateQueue queue = new WorldLightUpdateQueue();

        queue.add(batch);

        queue.prepareDrainPositions();

        Assertions.assertEquals(27, queue.pendingCount());
    }

    @Test
    void keepsThinColumnsAsSurfacePositions() {
        SectionLightUpdateBatch batch = new SectionLightUpdateBatch();
        for (int y = 48; y < 80; y++) {
            batch.addSurfaceCandidate(new BlockPos(4, y, -7));
        }
        WorldLightUpdateQueue queue = new WorldLightUpdateQueue();

        queue.add(batch);
        queue.prepareDrainPositions();

        Assertions.assertEquals(32, queue.pendingCount());
    }
}
