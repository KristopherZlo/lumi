package io.github.luma.domain.model;

/**
 * Lightweight index for selecting entity patch frames by old and new chunk membership.
 */
public record PatchEntityChunkIndex(
        String entityId,
        int frameChunkX,
        int frameChunkZ,
        Integer oldChunkX,
        Integer oldChunkZ,
        Integer newChunkX,
        Integer newChunkZ
) {

    public PatchEntityChunkIndex {
        entityId = entityId == null ? "" : entityId;
    }

    public ChunkPoint frameChunk() {
        return new ChunkPoint(this.frameChunkX, this.frameChunkZ);
    }

    public ChunkPoint oldChunk() {
        return this.oldChunkX == null || this.oldChunkZ == null
                ? null
                : new ChunkPoint(this.oldChunkX, this.oldChunkZ);
    }

    public ChunkPoint newChunk() {
        return this.newChunkX == null || this.newChunkZ == null
                ? null
                : new ChunkPoint(this.newChunkX, this.newChunkZ);
    }

    public boolean touches(ChunkPoint chunk) {
        if (chunk == null) {
            return false;
        }
        return chunk.equals(this.frameChunk())
                || chunk.equals(this.oldChunk())
                || chunk.equals(this.newChunk());
    }
}
