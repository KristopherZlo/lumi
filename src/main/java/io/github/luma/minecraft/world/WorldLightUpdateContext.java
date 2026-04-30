package io.github.luma.minecraft.world;

import java.util.ArrayDeque;
import java.util.Deque;

final class WorldLightUpdateContext {

    private static final ThreadLocal<Deque<WorldLightUpdateQueue>> ACTIVE_QUEUES =
            ThreadLocal.withInitial(ArrayDeque::new);

    private WorldLightUpdateContext() {
    }

    static void push(WorldLightUpdateQueue queue) {
        if (queue != null) {
            ACTIVE_QUEUES.get().push(queue);
        }
    }

    static void pop() {
        Deque<WorldLightUpdateQueue> queues = ACTIVE_QUEUES.get();
        if (!queues.isEmpty()) {
            queues.pop();
        }
        if (queues.isEmpty()) {
            ACTIVE_QUEUES.remove();
        }
    }

    static boolean enqueue(SectionLightUpdateBatch batch) {
        Deque<WorldLightUpdateQueue> queues = ACTIVE_QUEUES.get();
        if (queues.isEmpty()) {
            ACTIVE_QUEUES.remove();
            return false;
        }
        queues.peek().add(batch);
        return true;
    }
}
