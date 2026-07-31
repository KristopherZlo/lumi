package io.github.lumi.mixin;

import io.github.lumi.minecraft.world.SectionLightBatchScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.LevelChunk;
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

    @Override
    public void lumi$scheduleChunkRelight(LevelChunk chunk) {
        ChunkPos position = chunk.getPos();
        chunk.setLightCorrect(false);
        addTask(position.x, position.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE,
                () -> resetAndRelight(chunk, position));
        addTask(position.x, position.z, ThreadedLevelLightEngine.TaskType.POST_UPDATE,
                () -> chunk.setLightCorrect(true));
    }

    private void resetAndRelight(LevelChunk chunk, ChunkPos position) {
        super.retainData(position, false);
        super.setLightEnabled(position, false);
        for (int sectionY = getMinLightSection();
                sectionY < getMaxLightSection(); sectionY++) {
            queueSectionData(LightLayer.BLOCK, position, sectionY);
            queueSectionData(LightLayer.SKY, position, sectionY);
        }
        for (int sectionY = levelHeightAccessor.getMinSectionY();
                sectionY <= levelHeightAccessor.getMaxSectionY(); sectionY++) {
            super.updateSectionStatus(
                    net.minecraft.core.SectionPos.of(position, sectionY), true);
        }
        for (int index = 0; index < chunk.getSectionsCount(); index++) {
            if (!chunk.getSections()[index].hasOnlyAir()) {
                int sectionY = levelHeightAccessor.getSectionYFromSectionIndex(index);
                super.updateSectionStatus(
                        net.minecraft.core.SectionPos.of(position, sectionY), false);
            }
        }
        super.setLightEnabled(position, true);
        super.propagateLightSources(position);
    }

    private void queueSectionData(
            LightLayer layer, ChunkPos position, int sectionY) {
        super.queueSectionData(
                layer, net.minecraft.core.SectionPos.of(position, sectionY), null);
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
