package io.github.luma.minecraft.world;

import java.util.Arrays;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class ChunkHeightmapUpdatePlan {

    private final int[] sectionYByColumn = new int[16 * 16];
    private final int[] localIndexByColumn = new int[16 * 16];
    private final int[] worldYByColumn = new int[16 * 16];
    private final HeightmapColumnUpdater heightmapUpdater = new HeightmapColumnUpdater();
    private int columnCount;

    ChunkHeightmapUpdatePlan() {
        Arrays.fill(this.worldYByColumn, Integer.MIN_VALUE);
    }

    void record(int sectionY, int localIndex) {
        if (localIndex < 0 || localIndex >= SectionChangeMask.ENTRY_COUNT) {
            return;
        }
        int localX = SectionChangeMask.localX(localIndex);
        int localY = SectionChangeMask.localY(localIndex);
        int localZ = SectionChangeMask.localZ(localIndex);
        int columnIndex = localX | (localZ << 4);
        int worldY = (sectionY << 4) + localY;
        if (this.worldYByColumn[columnIndex] == Integer.MIN_VALUE) {
            this.columnCount += 1;
        } else if (worldY <= this.worldYByColumn[columnIndex]) {
            return;
        }
        this.sectionYByColumn[columnIndex] = sectionY;
        this.localIndexByColumn[columnIndex] = localIndex;
        this.worldYByColumn[columnIndex] = worldY;
    }

    int apply(LevelChunk chunk) {
        if (chunk == null || this.columnCount <= 0) {
            return 0;
        }
        int updatedColumns = 0;
        for (int columnIndex = 0; columnIndex < this.worldYByColumn.length; columnIndex++) {
            if (this.worldYByColumn[columnIndex] == Integer.MIN_VALUE) {
                continue;
            }
            int sectionY = this.sectionYByColumn[columnIndex];
            int localIndex = this.localIndexByColumn[columnIndex];
            int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                continue;
            }
            LevelChunkSection section = chunk.getSection(sectionIndex);
            if (section == null) {
                continue;
            }
            int localX = SectionChangeMask.localX(localIndex);
            int localY = SectionChangeMask.localY(localIndex);
            int localZ = SectionChangeMask.localZ(localIndex);
            BlockState state = section.getBlockState(localX, localY, localZ);
            this.heightmapUpdater.updateColumn(chunk, localX, this.worldYByColumn[columnIndex], localZ, state);
            updatedColumns += 1;
        }
        return updatedColumns;
    }

    int columnCount() {
        return this.columnCount;
    }

    int worldYForColumn(int localX, int localZ) {
        return this.worldYByColumn[(localX & 15) | ((localZ & 15) << 4)];
    }
}
