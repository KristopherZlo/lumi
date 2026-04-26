package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

public final class WorldMutationContext {

    private static final ThreadLocal<Deque<Frame>> SOURCE_STACK = ThreadLocal.withInitial(() -> {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(Frame.system());
        return stack;
    });

    private WorldMutationContext() {
    }

    public static WorldMutationSource currentSource() {
        return currentFrame().source();
    }

    public static String currentActor() {
        return currentFrame().actor();
    }

    public static String currentActionId() {
        return currentFrame().actionId();
    }

    public static boolean currentAccessAllowed() {
        return currentFrame().accessAllowed();
    }

    public static void pushSource(WorldMutationSource source) {
        Frame parent = currentFrame();
        SOURCE_STACK.get().push(new Frame(
                source == null ? WorldMutationSource.SYSTEM : source,
                parent.actor(),
                parent.actionId(),
                parent.accessAllowed()
        ));
    }

    public static void pushPlayerSource(WorldMutationSource source, String actor, boolean accessAllowed) {
        SOURCE_STACK.get().push(new Frame(
                source == null ? WorldMutationSource.PLAYER : source,
                actor == null || actor.isBlank() ? "player" : actor,
                UUID.randomUUID().toString(),
                accessAllowed
        ));
    }

    public static void pushExternalSource(WorldMutationSource source, String actor, String actionId) {
        SOURCE_STACK.get().push(new Frame(
                source == null ? WorldMutationSource.EXTERNAL_TOOL : source,
                actor == null || actor.isBlank() ? "external-tool" : actor,
                actionId == null || actionId.isBlank() ? UUID.randomUUID().toString() : actionId,
                true
        ));
    }

    public static void popSource() {
        Deque<Frame> stack = SOURCE_STACK.get();
        if (stack.size() > 1) {
            stack.pop();
        } else {
            stack.clear();
            stack.push(Frame.system());
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

    private static Frame currentFrame() {
        Frame frame = SOURCE_STACK.get().peek();
        return frame == null ? Frame.system() : frame;
    }

    private record Frame(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed
    ) {

        private static Frame system() {
            return new Frame(WorldMutationSource.SYSTEM, "world", "", false);
        }
    }
}
