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
        WorldMutationSource resolvedSource = source == null ? WorldMutationSource.SYSTEM : source;
        Frame parent = currentFrame();
        if (inheritsParentAction(resolvedSource)) {
            SOURCE_STACK.get().push(new Frame(
                    resolvedSource,
                    parent.actor(),
                    parent.actionId(),
                    parent.accessAllowed()
            ));
            return;
        }

        SOURCE_STACK.get().push(new Frame(
                resolvedSource,
                defaultActor(resolvedSource),
                "",
                false
        ));
    }

    public static void pushSource(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed
    ) {
        SOURCE_STACK.get().push(new Frame(
                source == null ? WorldMutationSource.SYSTEM : source,
                actor == null || actor.isBlank() ? "world" : actor,
                actionId == null || actionId.isBlank() ? "" : actionId,
                accessAllowed
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

    private static boolean inheritsParentAction(WorldMutationSource source) {
        return switch (source) {
            case PLAYER, ENTITY, EXPLOSIVE, EXTERNAL_TOOL, WORLDEDIT, FAWE, AXIOM -> true;
            case EXPLOSION, FLUID, FIRE, GROWTH, BLOCK_UPDATE, PISTON, FALLING_BLOCK, MOB, RESTORE, SYSTEM -> false;
        };
    }

    private static String defaultActor(WorldMutationSource source) {
        return switch (source) {
            case PLAYER -> "player";
            case ENTITY -> "entity";
            case EXPLOSIVE -> "explosive";
            case EXTERNAL_TOOL -> "external-tool";
            case WORLDEDIT -> "worldedit";
            case FAWE -> "fawe";
            case AXIOM -> "axiom";
            case EXPLOSION -> "explosion";
            case FLUID -> "fluid";
            case FIRE -> "fire";
            case GROWTH -> "growth";
            case BLOCK_UPDATE -> "block-update";
            case PISTON -> "piston";
            case FALLING_BLOCK -> "falling-block";
            case MOB -> "mob";
            case RESTORE, SYSTEM -> "world";
        };
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
