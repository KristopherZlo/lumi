package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.SectionKey;
import java.util.Objects;

public record ChunkCoordinate(int x, int z) {
    public static ChunkCoordinate from(HistoryKey key) {
        Objects.requireNonNull(key, "key");
        return key instanceof SectionKey section
                ? new ChunkCoordinate(section.chunkX(), section.chunkZ())
                : new ChunkCoordinate(
                        ((EntityChunkKey) key).chunkX(), ((EntityChunkKey) key).chunkZ());
    }
}
