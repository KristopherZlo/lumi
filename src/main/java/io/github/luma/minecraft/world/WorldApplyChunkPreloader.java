package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WorldApplyChunkPreloader {

    private static final int MAX_SYNC_FALLBACK_LOADS_PER_TICK = 32;

    private final List<ChunkPoint> chunks;
    private final Set<ChunkPoint> ticketedChunks = new LinkedHashSet<>();
    private int nextIndex;
    private int ticketIndex;
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

    static WorldApplyChunkPreloader forChunks(List<ChunkPoint> chunks) {
        return new WorldApplyChunkPreloader(chunks);
    }

    PreloadTickResult advance(ChunkPreloadAccess access, WorldApplyBudget budget, long deadlineNanos) {
        if (access == null || budget == null || this.complete()) {
            return new PreloadTickResult(0, 0, this.nextIndex, this.chunks.size(), this.complete(), 0, this.ticketedChunks.size(), 0);
        }

        int maxChunks = Math.max(0, budget.maxPreloadChunks());
        if (maxChunks <= 0) {
            return new PreloadTickResult(0, 0, this.nextIndex, this.chunks.size(), false, 0, this.ticketedChunks.size(), 0);
        }

        int newlyLoaded = 0;
        int alreadyLoaded = 0;
        int ticketed = 0;
        int syncFallbackLoads = 0;
        int processedLoads = 0;
        while (this.ticketIndex < this.chunks.size()
                && ticketed < maxChunks
                && System.nanoTime() < deadlineNanos) {
            ChunkPoint chunk = this.chunks.get(this.ticketIndex);
            access.acquireTicket(chunk);
            this.ticketedChunks.add(chunk);
            this.ticketIndex += 1;
            ticketed += 1;
        }

        while (this.nextIndex < this.chunks.size()
                && processedLoads < maxChunks
                && System.nanoTime() < deadlineNanos) {
            ChunkPoint chunk = this.chunks.get(this.nextIndex);
            if (!this.ticketedChunks.contains(chunk)) {
                access.acquireTicket(chunk);
                this.ticketedChunks.add(chunk);
                this.ticketIndex = Math.max(this.ticketIndex, this.nextIndex + 1);
                ticketed += 1;
            }
            if (access.isLoaded(chunk)) {
                alreadyLoaded += 1;
                this.nextIndex += 1;
                processedLoads += 1;
                continue;
            }
            if (syncFallbackLoads >= MAX_SYNC_FALLBACK_LOADS_PER_TICK) {
                break;
            }
            syncFallbackLoads += 1;
            if (access.load(chunk)) {
                newlyLoaded += 1;
                this.nextIndex += 1;
                processedLoads += 1;
                continue;
            }
            break;
        }
        return new PreloadTickResult(
                newlyLoaded,
                alreadyLoaded,
                this.nextIndex,
                this.chunks.size(),
                this.complete(),
                ticketed,
                this.ticketedChunks.size(),
                syncFallbackLoads
        );
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
            boolean complete,
            int ticketedChunks,
            int outstandingTickets,
            int syncFallbackLoads
    ) {
    }
}
