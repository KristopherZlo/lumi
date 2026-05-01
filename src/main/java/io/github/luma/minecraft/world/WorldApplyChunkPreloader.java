package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WorldApplyChunkPreloader {

    private final List<ChunkPoint> chunks;
    private final Set<ChunkPoint> ticketedChunks = new LinkedHashSet<>();
    private int nextIndex;
    private boolean released;

    private WorldApplyChunkPreloader(List<ChunkPoint> chunks) {
        this.chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    static WorldApplyChunkPreloader create(LocalQueue queue, WorldApplyProfile profile) {
        if (profile == WorldApplyProfile.NORMAL || queue == null) {
            return new WorldApplyChunkPreloader(List.of());
        }
        return new WorldApplyChunkPreloader(queue.uniqueChunks());
    }

    PreloadTickResult advance(ChunkPreloadAccess access, WorldApplyBudget budget, long deadlineNanos) {
        if (access == null || budget == null || this.complete()) {
            return new PreloadTickResult(0, 0, this.nextIndex, this.chunks.size(), this.complete());
        }

        int maxChunks = Math.max(0, budget.maxPreloadChunks());
        if (maxChunks <= 0) {
            return new PreloadTickResult(0, 0, this.nextIndex, this.chunks.size(), false);
        }

        int newlyLoaded = 0;
        int alreadyLoaded = 0;
        int processed = 0;
        while (this.nextIndex < this.chunks.size()
                && processed < maxChunks
                && System.nanoTime() < deadlineNanos) {
            ChunkPoint chunk = this.chunks.get(this.nextIndex);
            boolean wasLoaded = access.isLoaded(chunk);
            access.acquireTicket(chunk);
            this.ticketedChunks.add(chunk);
            if (wasLoaded) {
                alreadyLoaded += 1;
            } else if (access.load(chunk)) {
                newlyLoaded += 1;
            }
            this.nextIndex += 1;
            processed += 1;
        }
        return new PreloadTickResult(newlyLoaded, alreadyLoaded, this.nextIndex, this.chunks.size(), this.complete());
    }

    void release(ChunkPreloadAccess access) {
        if (this.released || access == null) {
            return;
        }
        this.released = true;
        for (ChunkPoint chunk : this.ticketedChunks) {
            access.releaseTicket(chunk);
        }
        this.ticketedChunks.clear();
    }

    boolean required() {
        return !this.chunks.isEmpty();
    }

    boolean complete() {
        return this.nextIndex >= this.chunks.size();
    }

    int totalChunks() {
        return this.chunks.size();
    }

    int completedChunks() {
        return this.nextIndex;
    }

    record PreloadTickResult(
            int newlyLoadedChunks,
            int alreadyLoadedChunks,
            int completedChunks,
            int totalChunks,
            boolean complete
    ) {
    }
}
