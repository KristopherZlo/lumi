package io.github.luma.minecraft.world;

import java.util.ArrayDeque;
import java.util.Deque;

final class WorldRedstoneReplayUpdateContext {

    private static final ThreadLocal<Deque<RedstoneReplayUpdateQueue>> ACTIVE_QUEUES =
            ThreadLocal.withInitial(ArrayDeque::new);

    private WorldRedstoneReplayUpdateContext() {
    }

    static void push(RedstoneReplayUpdateQueue queue) {
        if (queue != null) {
            ACTIVE_QUEUES.get().push(queue);
        }
    }

    static void pop() {
        Deque<RedstoneReplayUpdateQueue> queues = ACTIVE_QUEUES.get();
        if (!queues.isEmpty()) {
            queues.pop();
        }
        if (queues.isEmpty()) {
            ACTIVE_QUEUES.remove();
        }
    }

    static boolean enqueue(RedstoneReplayUpdateBatch batch) {
        Deque<RedstoneReplayUpdateQueue> queues = ACTIVE_QUEUES.get();
        if (queues.isEmpty()) {
            ACTIVE_QUEUES.remove();
            return false;
        }
        queues.peek().add(batch);
        return true;
    }
}
