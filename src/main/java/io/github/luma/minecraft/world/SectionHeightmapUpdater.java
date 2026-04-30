package io.github.luma.minecraft.world;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

final class SectionHeightmapUpdater {

    void update(LevelChunk chunk, int sectionY, int localIndex, BlockState state) {
        if (chunk == null || state == null) {
            return;
        }
        int localX = SectionChangeMask.localX(localIndex);
        int localY = SectionChangeMask.localY(localIndex);
        int localZ = SectionChangeMask.localZ(localIndex);
        int worldY = (sectionY << 4) + localY;
        for (var entry : chunk.getHeightmaps()) {
            Heightmap heightmap = entry.getValue();
            heightmap.update(localX, worldY, localZ, state);
        }
    }

    int updateChangedColumns(LevelChunk chunk, LevelChunkSection section, int sectionY, List<Integer> localIndexes) {
        if (chunk == null || section == null || localIndexes == null || localIndexes.isEmpty()) {
            return 0;
        }

        int[] highestByColumn = new int[256];
        java.util.Arrays.fill(highestByColumn, -1);
        for (int localIndex : localIndexes) {
            int columnIndex = SectionChangeMask.localX(localIndex) | (SectionChangeMask.localZ(localIndex) << 4);
            if (highestByColumn[columnIndex] < 0
                    || SectionChangeMask.localY(localIndex) > SectionChangeMask.localY(highestByColumn[columnIndex])) {
                highestByColumn[columnIndex] = localIndex;
            }
        }

        int updatedColumns = 0;
        for (int localIndex : highestByColumn) {
            if (localIndex < 0) {
                continue;
            }
            this.update(
                    chunk,
                    sectionY,
                    localIndex,
                    section.getBlockState(
                            SectionChangeMask.localX(localIndex),
                            SectionChangeMask.localY(localIndex),
                            SectionChangeMask.localZ(localIndex)
                    )
            );
            updatedColumns += 1;
        }
        return updatedColumns;
    }
}
