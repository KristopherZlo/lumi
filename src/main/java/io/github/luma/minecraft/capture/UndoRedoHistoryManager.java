package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.UndoRedoActionStack;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Volatile undo/redo history. Durable project history remains owned by the
 * save/restore services; this manager only groups live mutations by action id.
 */
public final class UndoRedoHistoryManager {

    private static final UndoRedoHistoryManager INSTANCE = new UndoRedoHistoryManager();

    private final Map<String, UndoRedoActionStack> projects = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();

    private UndoRedoHistoryManager() {
    }

    public static UndoRedoHistoryManager getInstance() {
        return INSTANCE;
    }

    public synchronized void recordAction(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            List<StoredBlockChange> blocks,
            List<StoredEntityChange> entities,
            Instant now
    ) {
        this.mutate(projectId, stack -> stack.recordAction(
                actionId, actor, projectId, dimensionId, blocks, entities, now
        ));
    }

    public synchronized void recordCurrentCausalAction(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            List<StoredBlockChange> blocks,
            List<StoredEntityChange> entities,
            Instant now
    ) {
        this.mutate(projectId, stack -> stack.recordCurrentCausalAction(
                actionId, actor, projectId, dimensionId, blocks, entities, now
        ));
    }

    public synchronized void recordDelayedEntityChanges(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            List<StoredEntityChange> entities,
            Instant actionStartedAt,
            Instant now
    ) {
        this.mutate(projectId, stack -> stack.recordDelayedEntityChanges(
                actionId, actor, projectId, dimensionId, entities, actionStartedAt, now
        ));
    }

    public synchronized UndoRedoActionStack.Selection selectUndo(String projectId) {
        UndoRedoActionStack stack = this.projects.get(projectId);
        return stack == null ? null : stack.selectUndo();
    }

    public synchronized UndoRedoActionStack.Selection selectUndo(String projectId, String actor) {
        return this.owned(this.selectUndo(projectId), actor);
    }

    public synchronized UndoRedoActionStack.Selection selectRedo(String projectId) {
        UndoRedoActionStack stack = this.projects.get(projectId);
        return stack == null ? null : stack.selectRedo();
    }

    public synchronized UndoRedoActionStack.Selection selectRedo(String projectId, String actor) {
        return this.owned(this.selectRedo(projectId), actor);
    }

    public synchronized boolean completeUndo(String projectId, UndoRedoActionStack.Selection selection) {
        return this.complete(projectId, selection, true);
    }

    public synchronized boolean completeRedo(String projectId, UndoRedoActionStack.Selection selection) {
        return this.complete(projectId, selection, false);
    }

    public synchronized List<UndoRedoAction> recentUndoActions(String projectId, int count) {
        UndoRedoActionStack stack = this.projects.get(projectId);
        return stack == null ? List.of() : stack.recentUndoActions(count);
    }

    public synchronized List<UndoRedoAction> recentRedoActions(String projectId, int count) {
        UndoRedoActionStack stack = this.projects.get(projectId);
        return stack == null ? List.of() : stack.recentRedoActions(count);
    }

    public synchronized RecentActionsSnapshot recentUndoActionsSnapshot(String projectId, int count) {
        return new RecentActionsSnapshot(this.revision(projectId), this.recentUndoActions(projectId, count));
    }

    public synchronized RecentActionsSnapshot recentRedoActionsSnapshot(String projectId, int count) {
        return new RecentActionsSnapshot(this.revision(projectId), this.recentRedoActions(projectId, count));
    }

    public synchronized UndoRedoActionsSnapshot recentUndoRedoActionsSnapshot(String projectId, int count) {
        return new UndoRedoActionsSnapshot(
                this.revision(projectId),
                this.recentUndoActions(projectId, count),
                this.recentRedoActions(projectId, count)
        );
    }

    public synchronized long revision(String projectId) {
        return projectId == null ? 0L : this.revisions.getOrDefault(projectId, 0L);
    }

    public synchronized boolean hasRedoAction(String projectId, String actionId) {
        UndoRedoActionStack stack = this.projects.get(projectId);
        return stack != null && stack.hasRedoAction(actionId);
    }

    public synchronized void clearProject(String projectId) {
        this.projects.remove(projectId);
        this.revisions.remove(projectId);
    }

    private void mutate(String projectId, StackMutation mutation) {
        if (projectId == null || projectId.isBlank() || mutation == null) {
            return;
        }
        UndoRedoActionStack stack = this.projects.computeIfAbsent(projectId, ignored -> new UndoRedoActionStack());
        long before = stack.revision();
        mutation.apply(stack);
        if (stack.revision() != before) {
            this.revisions.merge(projectId, 1L, Long::sum);
        }
    }

    private boolean complete(String projectId, UndoRedoActionStack.Selection selection, boolean undo) {
        UndoRedoActionStack stack = this.projects.get(projectId);
        if (stack == null) {
            return false;
        }
        long before = stack.revision();
        boolean completed = undo ? stack.completeUndo(selection) : stack.completeRedo(selection);
        if (stack.revision() != before) {
            this.revisions.merge(projectId, 1L, Long::sum);
        }
        return completed;
    }

    private UndoRedoActionStack.Selection owned(UndoRedoActionStack.Selection selection, String actor) {
        if (selection == null || actor == null || actor.isBlank()) {
            return selection;
        }
        return this.owner(selection.action().actor()).equals(this.owner(actor)) ? selection : null;
    }

    private String owner(String actor) {
        String value = actor == null ? "" : actor.trim();
        int separator = value.lastIndexOf(':');
        return (separator >= 0 ? value.substring(separator + 1) : value).toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface StackMutation {
        void apply(UndoRedoActionStack stack);
    }

    public record RecentActionsSnapshot(long revision, List<UndoRedoAction> actions) {
        public RecentActionsSnapshot {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record UndoRedoActionsSnapshot(
            long revision,
            List<UndoRedoAction> undoActions,
            List<UndoRedoAction> redoActions
    ) {
        public UndoRedoActionsSnapshot {
            undoActions = undoActions == null ? List.of() : List.copyOf(undoActions);
            redoActions = redoActions == null ? List.of() : List.copyOf(redoActions);
        }
    }
}
