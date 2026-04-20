package io.github.luma.domain.model;

public record ChunkDelta(
        int chunkX,
        int chunkZ,
        int changedBlocks
) {
}
