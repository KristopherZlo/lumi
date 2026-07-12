package io.github.luma.minecraft.world;

import net.minecraft.server.level.ServerLevel;

/** Owns preload and dispatch transitions for one prepared apply operation. */
final class PreparedApplyPhaseCoordinator {

    private WorldApplyChunkPreloader chunkPreloader;
    private GlobalDispatcher dispatcher;

    void initialize(PreparedApplyOperation prepared, WorldApplyProfile profile) {
        this.chunkPreloader = WorldApplyChunkPreloader.create(prepared.localQueue(), profile);
    }

    int totalPreloadChunks() {
        return this.chunkPreloader == null ? 0 : this.chunkPreloader.totalChunks();
    }

    boolean preloadPending() {
        return this.chunkPreloader != null
                && this.chunkPreloader.required()
                && !this.chunkPreloader.complete();
    }

    WorldApplyChunkPreloader.PreloadTickResult advancePreload(
            ServerLevel level,
            WorldApplyBudget budget,
            long deadlineNanos
    ) {
        return this.chunkPreloader.advance(new ServerLevelChunkPreloadAccess(level), budget, deadlineNanos);
    }

    boolean preloadComplete() {
        return this.chunkPreloader != null && this.chunkPreloader.complete();
    }

    boolean applyStarted() {
        return this.dispatcher != null;
    }

    void startApply(PreparedApplyOperation prepared) {
        this.dispatcher = new GlobalDispatcher();
        this.dispatcher.enqueue(prepared.localQueue());
    }

    ChunkBatch pollNext() {
        return this.dispatcher == null ? null : this.dispatcher.pollNext();
    }

    boolean hasPendingBatches() {
        return this.dispatcher != null && this.dispatcher.hasPending();
    }

    void releasePreloadTickets(ServerLevel level) {
        if (this.chunkPreloader != null) {
            this.chunkPreloader.release(new ServerLevelChunkPreloadAccess(level));
        }
    }
}
