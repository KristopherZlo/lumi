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
    private static final ThreadLocal<Integer> CAPTURE_SUPPRESSION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> ENTITY_REPLAY_DEPTH = ThreadLocal.withInitial(() -> 0);

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

    public static boolean captureSuppressed() {
        return CAPTURE_SUPPRESSION_DEPTH.get() > 0;
    }

    public static boolean internalWorldApplyActive() {
        return currentSource() == WorldMutationSource.RESTORE;
    }

    public static boolean historyEntityReplayActive() {
        return ENTITY_REPLAY_DEPTH.get() > 0;
    }

    public static SourceFrame pushSource(WorldMutationSource source) {
        WorldMutationSource resolvedSource = source == null ? WorldMutationSource.SYSTEM : source;
        Frame parent = currentFrame();
        if (inheritsParentAction(resolvedSource)) {
            SOURCE_STACK.get().push(new Frame(
                    resolvedSource,
                    parent.actor(),
                    parent.actionId(),
                    parent.accessAllowed()
            ));
            return new SourceFrame();
        }

        SOURCE_STACK.get().push(new Frame(
                resolvedSource,
                defaultActor(resolvedSource),
                "",
                false
        ));
        return new SourceFrame();
    }

    /**
     * Opens a secondary source that belongs to the currently active action,
     * such as bonemeal-triggered growth inside a player use action.
     */
    public static SourceFrame pushCausalSource(WorldMutationSource source) {
        WorldMutationSource resolvedSource = source == null ? WorldMutationSource.SYSTEM : source;
        Frame parent = currentFrame();
        if (parent.hasAction()) {
            SOURCE_STACK.get().push(new Frame(
                    resolvedSource,
                    parent.actor(),
                    parent.actionId(),
                    parent.accessAllowed()
            ));
            return new SourceFrame();
        }

        SOURCE_STACK.get().push(new Frame(
                resolvedSource,
                defaultActor(resolvedSource),
                "",
                false
        ));
        return new SourceFrame();
    }

    public static SourceFrame pushSource(
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
        return new SourceFrame();
    }

    public static SourceFrame pushPlayerSource(WorldMutationSource source, String actor, boolean accessAllowed) {
        WorldMutationSource resolvedSource = source == null ? WorldMutationSource.PLAYER : source;
        Frame parent = currentFrame();
        if (parent.hasAction()) {
            SOURCE_STACK.get().push(new Frame(
                    resolvedSource,
                    parent.actor(),
                    parent.actionId(),
                    parent.accessAllowed() || accessAllowed
            ));
            return new SourceFrame();
        }

        SOURCE_STACK.get().push(new Frame(
                resolvedSource,
                actor == null || actor.isBlank() ? "player" : actor,
                UUID.randomUUID().toString(),
                accessAllowed
        ));
        return new SourceFrame();
    }

    static SourceFrame pushExternalSource(WorldMutationSource source, String actor, String actionId) {
        return pushExternalSource(source, actor, actionId, false);
    }

    public static SourceFrame pushExternalSource(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed
    ) {
        SOURCE_STACK.get().push(new Frame(
                source == null ? WorldMutationSource.EXTERNAL_TOOL : source,
                actor == null || actor.isBlank() ? "external-tool" : actor,
                actionId == null || actionId.isBlank() ? UUID.randomUUID().toString() : actionId,
                accessAllowed
        ));
        return new SourceFrame();
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
        try (SourceFrame ignored = pushSource(source)) {
            runnable.run();
        }
    }

    public static void runWithCaptureSuppressed(Runnable runnable) {
        try (SuppressionFrame ignored = pushCaptureSuppression()) {
            runnable.run();
        }
    }

    public static SuppressionFrame pushCaptureSuppression() {
        CAPTURE_SUPPRESSION_DEPTH.set(CAPTURE_SUPPRESSION_DEPTH.get() + 1);
        return new SuppressionFrame();
    }

    public static void popCaptureSuppression() {
        int nextDepth = Math.max(0, CAPTURE_SUPPRESSION_DEPTH.get() - 1);
        if (nextDepth == 0) {
            CAPTURE_SUPPRESSION_DEPTH.remove();
        } else {
            CAPTURE_SUPPRESSION_DEPTH.set(nextDepth);
        }
    }

    public static EntityReplayFrame pushHistoryEntityReplay() {
        ENTITY_REPLAY_DEPTH.set(ENTITY_REPLAY_DEPTH.get() + 1);
        return new EntityReplayFrame();
    }

    public static void popHistoryEntityReplay() {
        int nextDepth = Math.max(0, ENTITY_REPLAY_DEPTH.get() - 1);
        if (nextDepth == 0) {
            ENTITY_REPLAY_DEPTH.remove();
        } else {
            ENTITY_REPLAY_DEPTH.set(nextDepth);
        }
    }

    private static Frame currentFrame() {
        Frame frame = SOURCE_STACK.get().peek();
        return frame == null ? Frame.system() : frame;
    }

    private static boolean inheritsParentAction(WorldMutationSource source) {
        return switch (source) {
            case PLAYER,
                    ENTITY,
                    EXPLOSION,
                    FLUID,
                    FIRE,
                    BLOCK_UPDATE,
                    PISTON,
                    FALLING_BLOCK,
                    EXPLOSIVE,
                    EXTERNAL_TOOL,
                    WORLDEDIT,
                    FAWE,
                    AXIOM -> true;
            case GROWTH, MOB, RESTORE, SYSTEM -> false;
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

        private boolean hasAction() {
            return this.actionId != null && !this.actionId.isBlank();
        }
    }

    public static final class SourceFrame implements AutoCloseable {

        private boolean closed;

        private SourceFrame() {
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            popSource();
        }
    }

    public static final class SuppressionFrame implements AutoCloseable {

        private boolean closed;

        private SuppressionFrame() {
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            popCaptureSuppression();
        }
    }

    public static final class EntityReplayFrame implements AutoCloseable {

        private boolean closed;

        private EntityReplayFrame() {
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            popHistoryEntityReplay();
        }
    }
}
