package io.github.lumi.minecraft.world;

/**
 * Keeps a mutated chunk resident until Lumi permits its next vanilla
 * publication.
 */
public interface ChunkDurabilityRetention {
    ChunkDurabilityRetention NONE = new ChunkDurabilityRetention() {
        @Override public void retain(int chunkX, int chunkZ) { }
        @Override public void release(int chunkX, int chunkZ) { }
    };

    void retain(int chunkX, int chunkZ);

    void release(int chunkX, int chunkZ);
}
