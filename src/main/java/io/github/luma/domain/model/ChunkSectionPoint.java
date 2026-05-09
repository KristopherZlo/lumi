package io.github.luma.domain.model;

public record ChunkSectionPoint(ChunkPoint chunk, int sectionY) {

    public ChunkSectionPoint {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk is required");
        }
    }

    public ChunkSectionPoint(int chunkX, int chunkZ, int sectionY) {
        this(new ChunkPoint(chunkX, chunkZ), sectionY);
    }

    public int chunkX() {
        return this.chunk.x();
    }

    public int chunkZ() {
        return this.chunk.z();
    }

    public static ChunkSectionPoint from(BlockPoint pos) {
        return new ChunkSectionPoint(ChunkPoint.from(pos), Math.floorDiv(pos.y(), 16));
    }
}
