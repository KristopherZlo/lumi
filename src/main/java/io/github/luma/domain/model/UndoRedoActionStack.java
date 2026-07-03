package io.github.luma.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

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
        StoredBlockChange recordableChange = this.withAppliedOldValue(dimensionId, change);
        if (action == null || !action.canAbsorbRelatedChange(dimensionId, recordableChange, now, maxIdle, chunkRadius)) {
            return this.revision;
        }

        return this.recordIntoAction(action, recordableChange, now, false);
    }

    public long recordCausalChange(
            String actionId,
            StoredBlockChange change,
            Instant now
    ) {
        if (actionId == null || actionId.isBlank() || change == null) {
            return this.revision;
        }

        UndoRedoAction action = this.undoStack.peekFirst();
        if (action == null || !action.id().equals(actionId)) {
            return this.revision;
        }

        StoredBlockChange recordableChange = this.withAppliedOldValue(action.dimensionId(), change);
        if (recordableChange == null || recordableChange.isNoOp()) {
            return this.revision;
        }
        return this.recordIntoAction(action, recordableChange, now, false);
    }

    public long recordCurrentCausalChange(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            StoredBlockChange change,
            Instant now
    ) {
        if (actionId == null || actionId.isBlank() || change == null) {
            return this.revision;
        }

        StoredBlockChange recordableChange = this.withAppliedOldValue(dimensionId, change);
        if (recordableChange == null || recordableChange.isNoOp()) {
            return this.revision;
        }

        UndoRedoAction action = this.findUndoAction(actionId);
        if (action == null) {
            action = new UndoRedoAction(actionId, actor, projectId, dimensionId, now, now);
            this.undoStack.addFirst(action);
            this.trimUndoStack();
        }

        return this.recordIntoAction(action, recordableChange, now, true);
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

    public long recordCausalEntityChange(
            String actionId,
            StoredEntityChange change,
            Instant now
    ) {
        if (actionId == null || actionId.isBlank() || change == null || change.isNoOp()) {
            return this.revision;
        }

        UndoRedoAction action = this.undoStack.peekFirst();
        if (action == null || !action.id().equals(actionId)) {
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

    public long recordDelayedEntityChange(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            StoredEntityChange change,
            Instant actionStartedAt,
            Instant now
    ) {
        return this.recordDelayedEntityChanges(
                actionId,
                actor,
                projectId,
                dimensionId,
                change == null ? List.of() : List.of(change),
                actionStartedAt,
                now
        );
    }

    public long recordDelayedEntityChanges(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            List<StoredEntityChange> changes,
            Instant actionStartedAt,
            Instant now
    ) {
        if (actionId == null || actionId.isBlank() || changes == null || changes.isEmpty()) {
            return this.revision;
        }

        List<StoredEntityChange> recordableChanges = changes.stream()
                .filter(change -> change != null && !change.isNoOp())
                .toList();
        if (recordableChanges.isEmpty()) {
            return this.revision;
        }

        UndoRedoAction action = this.findUndoAction(actionId);
        if (action == null) {
            if (this.findRedoAction(actionId) != null) {
                return this.revision;
            }
            Instant startedAt = actionStartedAt == null ? now : actionStartedAt;
            action = new UndoRedoAction(actionId, actor, projectId, dimensionId, startedAt, startedAt);
            this.insertUndoActionByStartedAt(action);
            this.trimUndoStack();
        }

        return this.recordSecondaryIntoExistingAction(action, List.of(), recordableChanges, now, true);
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

        return this.recordIntoExistingAction(action, changes, entityChanges, now, true);
    }

    public long recordCausalAction(
            String actionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            Instant now
    ) {
        if (actionId == null || actionId.isBlank()) {
            return this.revision;
        }

        UndoRedoAction action = this.undoStack.peekFirst();
        if (action == null || !action.id().equals(actionId)) {
            return this.revision;
        }

        return this.recordSecondaryIntoExistingAction(action, changes, entityChanges, now);
    }

    public long recordCurrentCausalAction(
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

        UndoRedoAction action = this.findUndoAction(actionId);
        if (action == null) {
            action = new UndoRedoAction(actionId, actor, projectId, dimensionId, now, now);
            this.undoStack.addFirst(action);
            this.trimUndoStack();
        }

        return this.recordSecondaryIntoExistingAction(action, changes, entityChanges, now, true);
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

    private UndoRedoAction findUndoAction(String actionId) {
        return this.findAction(this.undoStack, actionId);
    }

    private UndoRedoAction findRedoAction(String actionId) {
        return this.findAction(this.redoStack, actionId);
    }

    private UndoRedoAction findAction(Deque<UndoRedoAction> stack, String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return null;
        }
        for (UndoRedoAction action : stack) {
            if (action.id().equals(actionId)) {
                return action;
            }
        }
        return null;
    }

    private void insertUndoActionByStartedAt(UndoRedoAction action) {
        if (action == null) {
            return;
        }
        if (this.undoStack.isEmpty()) {
            this.undoStack.addFirst(action);
            return;
        }
        if (action.startedAt().isAfter(this.undoStack.peekFirst().startedAt())) {
            this.undoStack.addFirst(action);
            return;
        }
        if (!action.startedAt().isAfter(this.undoStack.peekLast().startedAt())) {
            this.undoStack.addLast(action);
            return;
        }

        List<UndoRedoAction> actions = new ArrayList<>(this.undoStack);
        int insertionIndex = actions.size();
        for (int index = 0; index < actions.size(); index++) {
            if (action.startedAt().isAfter(actions.get(index).startedAt())) {
                insertionIndex = index;
                break;
            }
        }
        actions.add(insertionIndex, action);
        this.undoStack.clear();
        this.undoStack.addAll(actions);
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

    private long recordIntoExistingAction(
            UndoRedoAction action,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            Instant now,
            boolean clearRedoOnMutation
    ) {
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
            if (clearRedoOnMutation) {
                this.redoStack.clear();
            }
            this.revision += 1;
        }
        return this.revision;
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

    private long recordSecondaryIntoExistingAction(
            UndoRedoAction action,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            Instant now
    ) {
        return this.recordSecondaryIntoExistingAction(action, changes, entityChanges, now, false);
    }

    private long recordSecondaryIntoExistingAction(
            UndoRedoAction action,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            Instant now,
            boolean clearRedoOnMutation
    ) {
        int before = action.size();
        boolean recorded = false;
        for (StoredBlockChange change : changes == null ? List.<StoredBlockChange>of() : changes) {
            StoredBlockChange recordableChange = this.withAppliedOldValue(action.dimensionId(), change);
            if (recordableChange == null || recordableChange.isNoOp()) {
                continue;
            }
            action.recordChange(recordableChange, now);
            recorded = true;
        }
        for (StoredEntityChange change : entityChanges == null ? List.<StoredEntityChange>of() : entityChanges) {
            if (change == null || change.isNoOp()) {
                continue;
            }
            action.recordEntityChange(change, now);
            recorded = true;
        }
        if (action.isEmpty()) {
            this.undoStack.remove(action);
        }
        if (recorded || before != action.size()) {
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

    private StoredBlockChange withAppliedOldValue(String dimensionId, StoredBlockChange change) {
        if (change == null || change.pos() == null) {
            return change;
        }
        StatePayload appliedState = this.appliedStateAt(dimensionId, change.pos());
        if (appliedState == null) {
            return change;
        }
        return change.withOldValue(appliedState);
    }

    private StatePayload appliedStateAt(String dimensionId, BlockPoint pos) {
        if (pos == null) {
            return null;
        }
        for (UndoRedoAction action : this.undoStack) {
            if (!Objects.equals(action.dimensionId(), dimensionId)) {
                continue;
            }
            StoredBlockChange existing = action.blockChangeAt(pos);
            if (existing != null) {
                return existing.newValue();
            }
        }
        return null;
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
