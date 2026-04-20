package io.github.luma.minecraft.world;

import java.util.ArrayList;
import java.util.List;

/**
 * World-scoped completed-first dispatcher for prepared local queues.
 */
public final class GlobalDispatcher {

    private static final int INCOMPLETE_WAITING_THRESHOLD = 64;
    private static final long INCOMPLETE_WAIT_MILLIS = 25L;

    private final List<LocalQueue> localQueues = new ArrayList<>();

    public void enqueue(LocalQueue queue) {
        if (queue != null) {
            this.localQueues.add(queue);
        }
    }

    public ChunkBatch pollNext() {
        for (LocalQueue queue : this.localQueues) {
            ChunkBatch batch = queue.pollCompleted();
            if (batch != null) {
                return batch;
            }
        }

        long nowNanos = System.nanoTime();
        int waitingIncomplete = 0;
        long oldestWaitMillis = Long.MAX_VALUE;
        for (LocalQueue queue : this.localQueues) {
            waitingIncomplete += queue.incompleteWaitingCount();
            oldestWaitMillis = Math.min(oldestWaitMillis, queue.oldestIncompleteWaitMillis(nowNanos));
        }

        if (waitingIncomplete < INCOMPLETE_WAITING_THRESHOLD && oldestWaitMillis < INCOMPLETE_WAIT_MILLIS) {
            return null;
        }

        for (LocalQueue queue : this.localQueues) {
            ChunkBatch batch = queue.pollIncomplete();
            if (batch != null) {
                return batch;
            }
        }
        return null;
    }

    public boolean hasPending() {
        for (LocalQueue queue : this.localQueues) {
            if (queue.hasPending()) {
                return true;
            }
        }
        return false;
    }

    public int totalPlacements() {
        int total = 0;
        for (LocalQueue queue : this.localQueues) {
            total += queue.totalPlacements();
        }
        return total;
    }
}
