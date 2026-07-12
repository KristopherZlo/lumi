package io.github.luma.domain.model;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Bounded in-memory history for one project and actor.
 *
 * <p>An action id is the only ownership rule. Immediate and deferred mutations
 * with the same id are folded into the same action.</p>
 */
public final class UndoRedoActionStack {

    private static final int DEFAULT_LIMIT = 64;

    private final int limit;
    private final Deque<UndoRedoAction> undo = new ArrayDeque<>();
    private final Deque<UndoRedoAction> redo = new ArrayDeque<>();
    private long revision;

    public UndoRedoActionStack() {
        this(DEFAULT_LIMIT);
    }

    public UndoRedoActionStack(int limit) {
        this.limit = Math.max(1, limit);
    }

    public long recordAction(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            List<StoredBlockChange> blocks,
            List<StoredEntityChange> entities,
            Instant now
    ) {
        return this.record(actionId, actor, projectId, dimensionId, blocks, entities, now, now, false, false);
    }

    public long recordCurrentCausalAction(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            List<StoredBlockChange> blocks,
            List<StoredEntityChange> entities,
            Instant now
    ) {
        return this.record(actionId, actor, projectId, dimensionId, blocks, entities, now, now, true, false);
    }

    public long recordDelayedEntityChanges(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            List<StoredEntityChange> entities,
            Instant actionStartedAt,
            Instant now
    ) {
        Instant startedAt = actionStartedAt == null ? now : actionStartedAt;
        return this.record(actionId, actor, projectId, dimensionId, List.of(), entities, startedAt, now, false, true);
    }

    public Selection selectUndo() {
        UndoRedoAction action = this.undo.peekFirst();
        return action == null ? null : new Selection(action.copy(), this.revision, action.version());
    }

    public Selection selectRedo() {
        UndoRedoAction action = this.redo.peekFirst();
        return action == null ? null : new Selection(action.copy(), this.revision, action.version());
    }

    public boolean completeUndo(Selection selection) {
        return this.move(selection, this.undo, this.redo);
    }

    public boolean completeRedo(Selection selection) {
        return this.move(selection, this.redo, this.undo);
    }

    public List<UndoRedoAction> recentUndoActions(int count) {
        return this.recent(this.undo, count);
    }

    public List<UndoRedoAction> recentRedoActions(int count) {
        return this.recent(this.redo, count);
    }

    public boolean canUndo() {
        return !this.undo.isEmpty();
    }

    public boolean canRedo() {
        return !this.redo.isEmpty();
    }

    public boolean hasRedoAction(String actionId) {
        return this.find(this.redo, actionId) != null;
    }

    public long revision() {
        return this.revision;
    }

    public void clear() {
        this.undo.clear();
        this.redo.clear();
        this.revision++;
    }

    private long record(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            List<StoredBlockChange> blocks,
            List<StoredEntityChange> entities,
            Instant startedAt,
            Instant now,
            boolean advanceChronology,
            boolean delayed
    ) {
        if (actionId == null || actionId.isBlank() || this.find(this.redo, actionId) != null) {
            return this.revision;
        }

        UndoRedoAction action = this.find(this.undo, actionId);
        boolean created = action == null;
        if (created) {
            action = new UndoRedoAction(actionId, actor, projectId, dimensionId, startedAt, startedAt);
            if (delayed) {
                this.addDelayed(action);
            } else {
                this.undo.addFirst(action);
            }
            this.trim(this.undo);
        }

        Instant recordedAt = advanceChronology && now.isAfter(action.updatedAt()) ? now : action.updatedAt();
        boolean changed = false;
        for (StoredBlockChange block : blocks == null ? List.<StoredBlockChange>of() : blocks) {
            changed |= action.recordChange(block, recordedAt);
        }
        for (StoredEntityChange entity : entities == null ? List.<StoredEntityChange>of() : entities) {
            changed |= action.recordEntityChange(entity, recordedAt);
        }
        if (action.isEmpty()) {
            this.undo.remove(action);
        } else if (changed && advanceChronology) {
            this.undo.remove(action);
            this.undo.addFirst(action);
        }
        if (changed) {
            this.redo.clear();
            this.revision++;
        }
        return this.revision;
    }

    private void addDelayed(UndoRedoAction action) {
        // ponytail: delayed actions only need to beat the current head; propagate an order token if deeper interleaving matters.
        UndoRedoAction current = this.undo.peekFirst();
        if (current == null || action.startedAt().isAfter(current.updatedAt())) {
            this.undo.addFirst(action);
        } else {
            this.undo.addLast(action);
        }
    }

    private boolean move(Selection selection, Deque<UndoRedoAction> from, Deque<UndoRedoAction> to) {
        if (selection == null || selection.action() == null) {
            return false;
        }
        UndoRedoAction current = this.find(from, selection.action().id());
        if (current == null || current.version() != selection.actionVersion()) {
            return false;
        }
        from.remove(current);
        to.addFirst(current);
        this.trim(to);
        this.revision++;
        return true;
    }

    private UndoRedoAction find(Deque<UndoRedoAction> actions, String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return null;
        }
        for (UndoRedoAction action : actions) {
            if (action.id().equals(actionId)) {
                return action;
            }
        }
        return null;
    }

    private List<UndoRedoAction> recent(Deque<UndoRedoAction> actions, int count) {
        if (count <= 0) {
            return List.of();
        }
        List<UndoRedoAction> result = new ArrayList<>(Math.min(actions.size(), count));
        Iterator<UndoRedoAction> iterator = actions.iterator();
        while (iterator.hasNext() && result.size() < count) {
            result.add(iterator.next().copy());
        }
        return List.copyOf(result);
    }

    private void trim(Deque<UndoRedoAction> actions) {
        while (actions.size() > this.limit) {
            actions.removeLast();
        }
    }

    public record Selection(UndoRedoAction action, long revision, long actionVersion) {
    }
}
