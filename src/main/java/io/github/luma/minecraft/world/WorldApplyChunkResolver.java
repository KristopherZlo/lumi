package io.github.luma.minecraft.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

final class WorldApplyChunkResolver {

    private WorldApplyChunkResolver() {
    }

    static LevelChunk loadedOrLoad(ServerLevel level, int chunkX, int chunkZ) {
        if (level == null) {
            return null;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk != null || !WorldApplyChunkLoadContext.allowsSynchronousLoad()) {
            return chunk;
        }
        return level.getChunk(chunkX, chunkZ);
    }
}
