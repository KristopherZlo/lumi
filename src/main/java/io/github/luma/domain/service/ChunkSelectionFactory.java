package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockChangeRecord;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChunkPoint;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

public final class ChunkSelectionFactory {

    private ChunkSelectionFactory() {
    }

    public static List<ChunkPoint> fromBounds(Bounds3i bounds) {
        LinkedHashMap<String, ChunkPoint> chunks = new LinkedHashMap<>();
        int minChunkX = bounds.min().x() >> 4;
        int maxChunkX = bounds.max().x() >> 4;
        int minChunkZ = bounds.min().z() >> 4;
        int maxChunkZ = bounds.max().z() >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkPoint chunk = new ChunkPoint(chunkX, chunkZ);
                chunks.put(key(chunk), chunk);
            }
        }

        return List.copyOf(chunks.values());
    }

    public static List<ChunkPoint> fromChanges(Collection<BlockChangeRecord> changes) {
        LinkedHashMap<String, ChunkPoint> chunks = new LinkedHashMap<>();
        for (BlockChangeRecord change : changes) {
            ChunkPoint chunk = ChunkPoint.from(change.pos());
            chunks.put(key(chunk), chunk);
        }
        return List.copyOf(chunks.values());
    }

    public static List<ChunkPoint> merge(Collection<ChunkPoint> first, Collection<ChunkPoint> second) {
        LinkedHashMap<String, ChunkPoint> chunks = new LinkedHashMap<>();
        addAll(chunks, first);
        addAll(chunks, second);
        return List.copyOf(chunks.values());
    }

    private static void addAll(LinkedHashMap<String, ChunkPoint> chunks, Collection<ChunkPoint> source) {
        for (ChunkPoint chunk : source) {
            chunks.put(key(chunk), chunk);
        }
    }

    private static String key(ChunkPoint chunk) {
        return chunk.x() + ":" + chunk.z();
    }
}
