package io.github.lumi.minecraft.world;

import java.util.concurrent.CompletableFuture;

/** Server-thread adapter for retaining and observing one requested chunk readiness. */
public interface ChunkLoadAccess {
    enum Readiness {
        TERRAIN,
        TERRAIN_WITH_NEIGHBORS,
        TERRAIN_AND_ENTITIES
    }

    CompletableFuture<Void> retain(ChunkCoordinate chunk);

    /** Starts retained chunks queued by the adapter; does nothing when none are queued. */
    default void startLoading() { }

    boolean isReady(ChunkCoordinate chunk);

    void release(ChunkCoordinate chunk);
}
