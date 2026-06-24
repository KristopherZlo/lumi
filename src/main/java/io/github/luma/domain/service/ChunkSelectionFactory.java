package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockChangeRecord;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

public final class ChunkSelectionFactory {

    private ChunkSelectionFactory() {
    }

    public static List<ChunkPoint> fromBounds(Bounds3i bounds) {
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        int minChunkX = bounds.min().x() >> 4;
        int maxChunkX = bounds.max().x() >> 4;
        int minChunkZ = bounds.min().z() >> 4;
        int maxChunkZ = bounds.max().z() >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkPoint(chunkX, chunkZ));
            }
        }

        return List.copyOf(chunks);
    }

    public static List<ChunkPoint> fromChanges(Collection<BlockChangeRecord> changes) {
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        for (BlockChangeRecord change : changes) {
            chunks.add(ChunkPoint.from(change.pos()));
        }
        return List.copyOf(chunks);
    }

    public static List<ChunkPoint> fromStoredChanges(Collection<StoredBlockChange> changes) {
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        for (StoredBlockChange change : changes) {
            chunks.add(ChunkPoint.from(change.pos()));
        }
        return List.copyOf(chunks);
    }

    public static List<ChunkPoint> merge(Collection<ChunkPoint> first, Collection<ChunkPoint> second) {
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        addAll(chunks, first);
        addAll(chunks, second);
        return List.copyOf(chunks);
    }

    private static void addAll(LinkedHashSet<ChunkPoint> chunks, Collection<ChunkPoint> source) {
        for (ChunkPoint chunk : source) {
            chunks.add(chunk);
        }
    }
}
