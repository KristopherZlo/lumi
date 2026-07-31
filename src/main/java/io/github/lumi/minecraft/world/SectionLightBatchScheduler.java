package io.github.lumi.minecraft.world;

import net.minecraft.world.level.chunk.LevelChunk;

/** Schedules sparse section checks or one native full-chunk relight. */
public interface SectionLightBatchScheduler {
    void lumi$scheduleSectionChecks(
            int chunkX, int sectionY, int chunkZ, short[] changedColumns);

    void lumi$scheduleChunkRelight(LevelChunk chunk);
}
