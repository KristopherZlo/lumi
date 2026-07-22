package io.github.lumi.minecraft.world;

/** Client synchronization emitted after one loaded chunk mutation. */
public record ChunkSyncResult(
        int fullChunkPackets,
        int sectionPackets,
        long payloadBytes) {
    public static final ChunkSyncResult NONE = new ChunkSyncResult(0, 0, 0);
}
