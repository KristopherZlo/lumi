package io.github.lumi.mixin;

import io.github.lumi.minecraft.world.SectionLightBatchScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Batches direct-section lighting checks onto the native lighting queue. */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin
        extends LevelLightEngine implements SectionLightBatchScheduler {
    protected ThreadedLevelLightEngineMixin(
            LightChunkGetter chunkProvider, boolean hasBlockLight, boolean hasSkyLight) {
        super(chunkProvider, hasBlockLight, hasSkyLight);
    }

    @Shadow
    protected abstract void addTask(
            int chunkX,
            int chunkZ,
            ThreadedLevelLightEngine.TaskType taskType,
            Runnable task);

    @Override
    public void lumi$scheduleSectionChecks(
            int chunkX, int sectionY, int chunkZ, short[] changedColumns) {
        short[] immutable = changedColumns.clone();
        addTask(chunkX, chunkZ, ThreadedLevelLightEngine.TaskType.PRE_UPDATE,
                () -> checkSection(chunkX, sectionY, chunkZ, immutable));
    }

    private void checkSection(
            int chunkX, int sectionY, int chunkZ, short[] changedColumns) {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                int changedX = changedColumns[(z << 4) | y] & 0xffff;
                for (int x = 0; changedX != 0; x++, changedX >>>= 1) {
                    if ((changedX & 1) != 0) {
                        super.checkBlock(position.set(
                                chunkX * 16 + x,
                                sectionY * 16 + y,
                                chunkZ * 16 + z));
                    }
                }
            }
        }
    }
}
