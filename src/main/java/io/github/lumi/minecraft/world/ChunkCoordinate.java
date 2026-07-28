package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.HistoryKey;
import java.util.Objects;

public record ChunkCoordinate(int x, int z) {
    public static ChunkCoordinate from(HistoryKey key) {
        Objects.requireNonNull(key, "key");
        return new ChunkCoordinate(key.chunkX(), key.chunkZ());
    }
}
