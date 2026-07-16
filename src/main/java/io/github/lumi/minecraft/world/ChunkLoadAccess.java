package io.github.lumi.minecraft.world;

import java.util.concurrent.CompletableFuture;

/** Server-thread adapter for retaining and observing one fully loaded chunk. */
public interface ChunkLoadAccess {
    CompletableFuture<Void> retain(ChunkCoordinate chunk);

    boolean isReady(ChunkCoordinate chunk);

    void release(ChunkCoordinate chunk);
}
