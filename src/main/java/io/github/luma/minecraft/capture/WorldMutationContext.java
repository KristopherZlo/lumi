package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.ArrayDeque;
import java.util.Deque;

public final class WorldMutationContext {

    private static final ThreadLocal<Deque<WorldMutationSource>> SOURCE_STACK = ThreadLocal.withInitial(() -> {
        Deque<WorldMutationSource> stack = new ArrayDeque<>();
        stack.push(WorldMutationSource.SYSTEM);
        return stack;
    });

    private WorldMutationContext() {
    }

    public static WorldMutationSource currentSource() {
        return SOURCE_STACK.get().peek();
    }

    public static void pushSource(WorldMutationSource source) {
        SOURCE_STACK.get().push(source);
    }

    public static void popSource() {
        Deque<WorldMutationSource> stack = SOURCE_STACK.get();
        if (stack.size() > 1) {
            stack.pop();
        } else {
            stack.clear();
            stack.push(WorldMutationSource.SYSTEM);
        }
    }

    public static void runWithSource(WorldMutationSource source, Runnable runnable) {
        pushSource(source);
        try {
            runnable.run();
        } finally {
            popSource();
        }
    }
}
