package io.github.lumi.minecraft.world;

/** Schedules one lighting task for all changed cells in a section. */
public interface SectionLightBatchScheduler {
    void lumi$scheduleSectionChecks(
            int chunkX, int sectionY, int chunkZ, short[] changedColumns);
}
