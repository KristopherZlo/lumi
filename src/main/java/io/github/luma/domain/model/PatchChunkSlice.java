package io.github.luma.domain.model;

public record PatchChunkSlice(
        int chunkX,
        int chunkZ,
        int changeCount,
        long dataOffsetBytes,
        int dataLengthBytes
) {

    public ChunkPoint chunk() {
        return new ChunkPoint(this.chunkX, this.chunkZ);
    }
}
