package io.github.luma.minecraft.world;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
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
}
