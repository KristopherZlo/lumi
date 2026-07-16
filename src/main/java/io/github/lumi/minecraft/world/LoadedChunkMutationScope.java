package io.github.lumi.minecraft.world;

import java.util.HashSet;
import java.util.Set;

/**
 * Distinguishes live world chunks from LevelChunk instances still being
 * generated or loaded.
 */
public final class LoadedChunkMutationScope {
    private final Set<ChunkCoordinate> loaded = new HashSet<>();

    public synchronized void loaded(int chunkX, int chunkZ) {
        loaded.add(new ChunkCoordinate(chunkX, chunkZ));
    }

    public synchronized void unloaded(int chunkX, int chunkZ) {
        loaded.remove(new ChunkCoordinate(chunkX, chunkZ));
    }

    public synchronized boolean contains(int chunkX, int chunkZ) {
        return loaded.contains(new ChunkCoordinate(chunkX, chunkZ));
    }
}
