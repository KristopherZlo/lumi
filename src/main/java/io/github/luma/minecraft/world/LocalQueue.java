package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-operation local queue of prepared chunk batches.
 */
public final class LocalQueue {

    private final long createdAtNanos = System.nanoTime();
    private final Deque<ChunkBatch> completed = new ArrayDeque<>();
    private final Deque<QueuedChunkBatch> incomplete = new ArrayDeque<>();
    private int totalPlacements = 0;
    private int totalWorkUnits = 0;

    public static LocalQueue completed(List<ChunkBatch> batches) {
        LocalQueue queue = new LocalQueue();
        if (batches != null) {
            for (ChunkBatch batch : batches) {
                queue.offer(batch);
            }
        }
        return queue;
    }

    public void offer(ChunkBatch batch) {
        if (batch == null) {
            return;
        }
        this.totalPlacements += batch.totalPlacements();
        this.totalWorkUnits += batch.totalWorkUnits();
        if (batch.state() == BatchState.COMPLETE) {
            this.completed.addLast(batch);
        } else {
            this.incomplete.addLast(new QueuedChunkBatch(batch, System.nanoTime()));
        }
    }

    public ChunkBatch pollCompleted() {
        return this.completed.pollFirst();
    }

    public ChunkBatch pollIncomplete() {
        QueuedChunkBatch batch = this.incomplete.pollFirst();
        return batch == null ? null : batch.batch();
    }

    public boolean hasPending() {
        return !this.completed.isEmpty() || !this.incomplete.isEmpty();
    }

    public int completedCount() {
        return this.completed.size();
    }

    public int incompleteCount() {
        return this.incomplete.size();
    }

    public int totalPlacements() {
        return this.totalPlacements;
    }

    public int totalWorkUnits() {
        return this.totalWorkUnits;
    }

    public List<ChunkPoint> uniqueChunks() {
        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        for (ChunkBatch batch : this.completed) {
            chunks.add(batch.chunk());
        }
        for (QueuedChunkBatch queued : this.incomplete) {
            chunks.add(queued.batch().chunk());
        }
        return List.copyOf(chunks);
    }

    public int incompleteWaitingCount() {
        return this.incomplete.size();
    }

    public long oldestIncompleteWaitMillis(long nowNanos) {
        QueuedChunkBatch oldest = this.incomplete.peekFirst();
        if (oldest == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, (nowNanos - oldest.enqueuedAtNanos()) / 1_000_000L);
    }

    public long createdAtNanos() {
        return this.createdAtNanos;
    }

    private record QueuedChunkBatch(ChunkBatch batch, long enqueuedAtNanos) {
    }
}
