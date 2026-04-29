package io.github.luma.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Bounded runtime undo/redo stack for one project.
 */
public final class UndoRedoActionStack {

    private static final int DEFAULT_LIMIT = 64;

    private final int limit;
    private final Deque<UndoRedoAction> undoStack = new ArrayDeque<>();
    private final Deque<UndoRedoAction> redoStack = new ArrayDeque<>();
    private long revision = 0L;

    public UndoRedoActionStack() {
        this(DEFAULT_LIMIT);
    }

    public UndoRedoActionStack(int limit) {
        this.limit = Math.max(1, limit);
    }

    public long recordChange(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            StoredBlockChange change,
            Instant now
    ) {
        if (actionId == null || actionId.isBlank() || change == null || change.isNoOp()) {
            return this.revision;
        }

        UndoRedoAction action = this.undoStack.peekFirst();
        if (action == null || !action.id().equals(actionId)) {
            action = new UndoRedoAction(actionId, actor, projectId, dimensionId, now, now);
            this.undoStack.addFirst(action);
            this.trimUndoStack();
        }

        return this.recordIntoAction(action, change, now, true);
    }

    public long recordRelatedChange(
            String dimensionId,
            StoredBlockChange change,
            Instant now,
            Duration maxIdle,
            int chunkRadius
    ) {
        UndoRedoAction action = this.undoStack.peekFirst();
        if (action == null || !action.canAbsorbRelatedChange(dimensionId, change, now, maxIdle, chunkRadius)) {
            return this.revision;
        }

        return this.recordIntoAction(action, change, now, false);
    }

    public long recordRelatedEntityChange(
            String dimensionId,
            StoredEntityChange change,
            Instant now,
            Duration maxIdle,
            int chunkRadius
    ) {
        UndoRedoAction action = this.undoStack.peekFirst();
        if (action == null || !action.canAbsorbRelatedEntityChange(dimensionId, change, now, maxIdle, chunkRadius)) {
            return this.revision;
        }

        return this.recordEntityIntoAction(action, change, now, false);
    }

    public long recordEntityChange(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            StoredEntityChange change,
            Instant now
    ) {
        if (actionId == null || actionId.isBlank() || change == null || change.isNoOp()) {
            return this.revision;
        }

        UndoRedoAction action = this.undoStack.peekFirst();
        if (action == null || !action.id().equals(actionId)) {
            action = new UndoRedoAction(actionId, actor, projectId, dimensionId, now, now);
            this.undoStack.addFirst(action);
            this.trimUndoStack();
        }

        return this.recordEntityIntoAction(action, change, now, true);
    }

    public long recordAction(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            Instant now
    ) {
        if (actionId == null || actionId.isBlank()) {
            return this.revision;
        }

        UndoRedoAction action = this.undoStack.peekFirst();
        if (action == null || !action.id().equals(actionId)) {
            action = new UndoRedoAction(actionId, actor, projectId, dimensionId, now, now);
            this.undoStack.addFirst(action);
            this.trimUndoStack();
        }

        int before = action.size();
        for (StoredBlockChange change : changes == null ? List.<StoredBlockChange>of() : changes) {
            action.recordChange(change, now);
        }
        for (StoredEntityChange change : entityChanges == null ? List.<StoredEntityChange>of() : entityChanges) {
            action.recordEntityChange(change, now);
        }
        if (action.isEmpty()) {
            this.undoStack.remove(action);
        }
        if (before != action.size() || !action.isEmpty()) {
            this.redoStack.clear();
            this.revision += 1;
        }
        return this.revision;
    }

    public Selection selectUndo() {
        UndoRedoAction action = this.undoStack.peekFirst();
        return action == null ? null : new Selection(action.copy(), this.revision);
    }

    public Selection selectRedo() {
        UndoRedoAction action = this.redoStack.peekFirst();
        return action == null ? null : new Selection(action.copy(), this.revision);
    }

    public void completeUndo(Selection selection) {
        if (selection == null || !this.selectionCanComplete(this.undoStack, selection)) {
            return;
        }

        UndoRedoAction removed = this.removeById(this.undoStack, selection.action().id());
        if (removed == null) {
            return;
        }

        this.redoStack.addFirst(removed);
        this.trimRedoStack();
        this.revision += 1;
    }

    public void completeRedo(Selection selection) {
        if (selection == null || !this.selectionCanComplete(this.redoStack, selection)) {
            return;
        }

        UndoRedoAction removed = this.removeById(this.redoStack, selection.action().id());
        if (removed == null) {
            return;
        }

        this.undoStack.addFirst(removed);
        this.trimUndoStack();
        this.revision += 1;
    }

    public List<UndoRedoAction> recentUndoActions(int count) {
        return this.recentActions(this.undoStack, count);
    }

    public List<UndoRedoAction> recentRedoActions(int count) {
        return this.recentActions(this.redoStack, count);
    }

    private List<UndoRedoAction> recentActions(Deque<UndoRedoAction> stack, int count) {
        if (count <= 0 || stack.isEmpty()) {
            return List.of();
        }

        List<UndoRedoAction> actions = new ArrayList<>();
        for (UndoRedoAction action : stack) {
            actions.add(action.copy());
            if (actions.size() >= count) {
                break;
            }
        }
        return List.copyOf(actions);
    }

    public boolean canUndo() {
        return !this.undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !this.redoStack.isEmpty();
    }

    public long revision() {
        return this.revision;
    }

    public void clear() {
        this.undoStack.clear();
        this.redoStack.clear();
        this.revision += 1;
    }

    private long recordIntoAction(
            UndoRedoAction action,
            StoredBlockChange change,
            Instant now,
            boolean clearRedoOnMutation
    ) {
        int before = action.size();
        action.recordChange(change, now);
        if (action.isEmpty()) {
            this.undoStack.remove(action);
        }
        if (before != action.size() || !action.isEmpty()) {
            if (clearRedoOnMutation) {
                this.redoStack.clear();
            }
            this.revision += 1;
        }
        return this.revision;
    }

    private long recordEntityIntoAction(
            UndoRedoAction action,
            StoredEntityChange change,
            Instant now,
            boolean clearRedoOnMutation
    ) {
        int before = action.size();
        action.recordEntityChange(change, now);
        if (action.isEmpty()) {
            this.undoStack.remove(action);
        }
        if (before != action.size() || !action.isEmpty()) {
            if (clearRedoOnMutation) {
                this.redoStack.clear();
            }
            this.revision += 1;
        }
        return this.revision;
    }

    private boolean selectionCanComplete(Deque<UndoRedoAction> stack, Selection selection) {
        if (this.revision == selection.revision()) {
            return true;
        }
        UndoRedoAction current = stack.peekFirst();
        return current != null && current.id().equals(selection.action().id());
    }

    private UndoRedoAction removeById(Deque<UndoRedoAction> stack, String actionId) {
        Iterator<UndoRedoAction> iterator = stack.iterator();
        while (iterator.hasNext()) {
            UndoRedoAction action = iterator.next();
            if (action.id().equals(actionId)) {
                iterator.remove();
                return action;
            }
        }
        return null;
    }

    private void trimUndoStack() {
        while (this.undoStack.size() > this.limit) {
            this.undoStack.removeLast();
        }
    }

    private void trimRedoStack() {
        while (this.redoStack.size() > this.limit) {
            this.redoStack.removeLast();
        }
    }

    public record Selection(UndoRedoAction action, long revision) {
    }
}
