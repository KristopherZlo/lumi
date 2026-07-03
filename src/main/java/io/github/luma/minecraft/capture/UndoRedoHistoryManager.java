package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.UndoRedoActionStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory per-project undo/redo history for live builder actions.
 */
public final class UndoRedoHistoryManager {

    private static final UndoRedoHistoryManager INSTANCE = new UndoRedoHistoryManager();

    private final Map<String, Map<String, UndoRedoActionStack>> projectStacks = new HashMap<>();
    private final Map<String, Map<String, String>> projectKeyOwners = new HashMap<>();

    private UndoRedoHistoryManager() {
    }

    public static UndoRedoHistoryManager getInstance() {
        return INSTANCE;
    }

    public synchronized void recordChange(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            StoredBlockChange change,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stack(projectId, actor);
        stack.recordChange(actionId, actor, projectId, dimensionId, change, now);
        this.markOwner(projectId, actionId, dimensionId, change);
    }

    public synchronized void recordRelatedChange(
            String projectId,
            String dimensionId,
            StoredBlockChange change,
            Instant now,
            Duration maxIdle,
            int chunkRadius
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.latestUndoStack(projectId);
        if (stack == null) {
            return;
        }
        UndoRedoAction before = stack.selectUndo() == null ? null : stack.selectUndo().action();
        long revision = stack.revision();
        stack.recordRelatedChange(dimensionId, change, now, maxIdle, chunkRadius);
        if (before != null && stack.revision() != revision) {
            this.markOwner(projectId, before.id(), dimensionId, change);
        }
    }

    public synchronized void recordCausalChange(
            String projectId,
            String actionId,
            StoredBlockChange change,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stackForUndoAction(projectId, actionId);
        if (stack == null) {
            return;
        }
        UndoRedoAction action = stack.selectUndo().action();
        stack.recordCausalChange(actionId, change, now);
        this.markOwner(projectId, actionId, action.dimensionId(), change);
    }

    public synchronized void recordCurrentCausalChange(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            StoredBlockChange change,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stack(projectId, actor);
        stack.recordCurrentCausalChange(actionId, actor, projectId, dimensionId, change, now);
        this.markOwner(projectId, actionId, dimensionId, change);
    }

    public synchronized void recordRelatedEntityChange(
            String projectId,
            String dimensionId,
            StoredEntityChange change,
            Instant now,
            Duration maxIdle,
            int chunkRadius
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.latestUndoStack(projectId);
        if (stack == null) {
            return;
        }
        UndoRedoAction before = stack.selectUndo() == null ? null : stack.selectUndo().action();
        long revision = stack.revision();
        stack.recordRelatedEntityChange(dimensionId, change, now, maxIdle, chunkRadius);
        if (before != null && stack.revision() != revision) {
            this.markOwner(projectId, before.id(), dimensionId, change);
        }
    }

    public synchronized void recordCausalEntityChange(
            String projectId,
            String actionId,
            StoredEntityChange change,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stackForUndoAction(projectId, actionId);
        if (stack == null) {
            return;
        }
        UndoRedoAction action = stack.selectUndo().action();
        stack.recordCausalEntityChange(actionId, change, now);
        this.markOwner(projectId, actionId, action.dimensionId(), change);
    }

    public synchronized void recordEntityChange(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            StoredEntityChange change,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stack(projectId, actor);
        stack.recordEntityChange(actionId, actor, projectId, dimensionId, change, now);
        this.markOwner(projectId, actionId, dimensionId, change);
    }

    public synchronized void recordDelayedEntityChange(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            StoredEntityChange change,
            Instant actionStartedAt,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stack(projectId, actor);
        stack.recordDelayedEntityChange(
                actionId,
                actor,
                projectId,
                dimensionId,
                change,
                actionStartedAt,
                now
        );
        this.markOwner(projectId, actionId, dimensionId, change);
    }

    public synchronized void recordDelayedEntityChanges(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            List<StoredEntityChange> changes,
            Instant actionStartedAt,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stack(projectId, actor);
        stack.recordDelayedEntityChanges(
                actionId,
                actor,
                projectId,
                dimensionId,
                changes,
                actionStartedAt,
                now
        );
        this.markOwners(projectId, actionId, dimensionId, List.of(), changes);
    }

    public synchronized void recordAction(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stack(projectId, actor);
        stack.recordAction(actionId, actor, projectId, dimensionId, changes, entityChanges, now);
        this.markOwners(projectId, actionId, dimensionId, changes, entityChanges);
    }

    public synchronized void recordCausalAction(
            String projectId,
            String actionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stackForUndoAction(projectId, actionId);
        if (stack == null) {
            return;
        }
        UndoRedoAction action = stack.selectUndo().action();
        stack.recordCausalAction(actionId, changes, entityChanges, now);
        this.markOwners(projectId, actionId, action.dimensionId(), changes, entityChanges);
    }

    public synchronized void recordCurrentCausalAction(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stack(projectId, actor);
        stack.recordCurrentCausalAction(
                actionId,
                actor,
                projectId,
                dimensionId,
                changes,
                entityChanges,
                now
        );
        this.markOwners(projectId, actionId, dimensionId, changes, entityChanges);
    }

    public synchronized UndoRedoActionStack.Selection selectUndo(String projectId) {
        return this.latestSelection(projectId, true);
    }

    public synchronized UndoRedoActionStack.Selection selectUndo(String projectId, String actor) {
        UndoRedoActionStack stack = this.stack(projectId, actor, false);
        if (stack == null) {
            return null;
        }
        UndoRedoActionStack.Selection selection = stack.selectUndo();
        return this.isCurrent(projectId, selection) ? selection : null;
    }

    public synchronized UndoRedoActionStack.Selection selectRedo(String projectId) {
        return this.latestSelection(projectId, false);
    }

    public synchronized UndoRedoActionStack.Selection selectRedo(String projectId, String actor) {
        UndoRedoActionStack stack = this.stack(projectId, actor, false);
        if (stack == null) {
            return null;
        }
        UndoRedoActionStack.Selection selection = stack.selectRedo();
        return this.isCurrent(projectId, selection) ? selection : null;
    }

    public synchronized void completeUndo(String projectId, UndoRedoActionStack.Selection selection) {
        UndoRedoActionStack stack = this.stackForSelection(projectId, selection);
        if (stack != null) {
            stack.completeUndo(selection);
        }
    }

    public synchronized void completeRedo(String projectId, UndoRedoActionStack.Selection selection) {
        UndoRedoActionStack stack = this.stackForSelection(projectId, selection);
        if (stack != null) {
            stack.completeRedo(selection);
        }
    }

    public synchronized List<UndoRedoAction> recentUndoActions(String projectId, int count) {
        return this.recentActions(projectId, count, true);
    }

    public synchronized List<UndoRedoAction> recentRedoActions(String projectId, int count) {
        return this.recentActions(projectId, count, false);
    }

    public synchronized RecentActionsSnapshot recentUndoActionsSnapshot(String projectId, int count) {
        if (projectId == null || projectId.isBlank()) {
            return new RecentActionsSnapshot(0L, List.of());
        }
        return new RecentActionsSnapshot(this.revision(projectId), this.recentUndoActions(projectId, count));
    }

    public synchronized RecentActionsSnapshot recentRedoActionsSnapshot(String projectId, int count) {
        if (projectId == null || projectId.isBlank()) {
            return new RecentActionsSnapshot(0L, List.of());
        }
        return new RecentActionsSnapshot(this.revision(projectId), this.recentRedoActions(projectId, count));
    }

    public synchronized UndoRedoActionsSnapshot recentUndoRedoActionsSnapshot(String projectId, int count) {
        if (projectId == null || projectId.isBlank()) {
            return new UndoRedoActionsSnapshot(0L, List.of(), List.of());
        }
        return new UndoRedoActionsSnapshot(
                this.revision(projectId),
                this.recentUndoActions(projectId, count),
                this.recentRedoActions(projectId, count)
        );
    }

    public synchronized long revision(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return 0L;
        }
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null || stacks.isEmpty()) {
            return 0L;
        }
        return stacks.values().stream()
                .mapToLong(UndoRedoActionStack::revision)
                .max()
                .orElse(0L);
    }

    public synchronized void clearProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        this.projectStacks.remove(projectId);
        this.projectKeyOwners.remove(projectId);
    }

    private UndoRedoActionStack stack(String projectId, String actor) {
        return this.stack(projectId, actor, true);
    }

    private UndoRedoActionStack stack(String projectId, String actor, boolean create) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null) {
            if (!create) {
                return null;
            }
            stacks = new HashMap<>();
            this.projectStacks.put(projectId, stacks);
        }
        if (!create) {
            return stacks.get(this.actorKey(actor));
        }
        return stacks.computeIfAbsent(this.actorKey(actor), ignored -> new UndoRedoActionStack());
    }

    private UndoRedoActionStack latestUndoStack(String projectId) {
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null) {
            return null;
        }
        return stacks.values().stream()
                .filter(stack -> stack.selectUndo() != null)
                .max(Comparator.comparing(stack -> stack.selectUndo().action().updatedAt()))
                .orElse(null);
    }

    private UndoRedoActionStack stackForUndoAction(String projectId, String actionId) {
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null || actionId == null || actionId.isBlank()) {
            return null;
        }
        for (UndoRedoActionStack stack : stacks.values()) {
            if (stack.recentUndoActions(64).stream().anyMatch(action -> action.id().equals(actionId))) {
                return stack;
            }
        }
        return null;
    }

    private UndoRedoActionStack stackForSelection(String projectId, UndoRedoActionStack.Selection selection) {
        if (selection == null || selection.action() == null) {
            return null;
        }
        return this.stack(projectId, selection.action().actor(), false);
    }

    private UndoRedoActionStack.Selection latestSelection(String projectId, boolean undo) {
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null || stacks.isEmpty()) {
            return null;
        }
        return stacks.values().stream()
                .map(stack -> undo ? stack.selectUndo() : stack.selectRedo())
                .filter(selection -> this.isCurrent(projectId, selection))
                .max(Comparator.comparing(selection -> selection.action().updatedAt()))
                .orElse(null);
    }

    private List<UndoRedoAction> recentActions(String projectId, int count, boolean undo) {
        if (count <= 0) {
            return List.of();
        }
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        List<UndoRedoAction> actions = new ArrayList<>();
        for (UndoRedoActionStack stack : stacks.values()) {
            actions.addAll(undo ? stack.recentUndoActions(count) : stack.recentRedoActions(count));
        }
        return actions.stream()
                .filter(action -> this.actionIsCurrent(projectId, action))
                .sorted(Comparator.comparing(UndoRedoAction::updatedAt).reversed())
                .limit(count)
                .toList();
    }

    private boolean isCurrent(String projectId, UndoRedoActionStack.Selection selection) {
        return selection != null && this.actionIsCurrent(projectId, selection.action());
    }

    private boolean actionIsCurrent(String projectId, UndoRedoAction action) {
        if (action == null) {
            return false;
        }
        Map<String, String> owners = this.projectKeyOwners.get(projectId);
        if (owners == null || owners.isEmpty()) {
            return true;
        }
        for (String key : this.keys(action)) {
            String owner = owners.get(key);
            if (owner != null && !owner.equals(action.id())) {
                return false;
            }
        }
        return true;
    }

    private void markOwners(
            String projectId,
            String actionId,
            String dimensionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) {
        for (StoredBlockChange change : changes == null ? List.<StoredBlockChange>of() : changes) {
            this.markOwner(projectId, actionId, dimensionId, change);
        }
        for (StoredEntityChange change : entityChanges == null ? List.<StoredEntityChange>of() : entityChanges) {
            this.markOwner(projectId, actionId, dimensionId, change);
        }
    }

    private void markOwner(String projectId, String actionId, String dimensionId, StoredBlockChange change) {
        if (projectId == null || projectId.isBlank() || actionId == null || actionId.isBlank()
                || change == null || change.pos() == null) {
            return;
        }
        this.projectKeyOwners.computeIfAbsent(projectId, ignored -> new HashMap<>())
                .put("b:" + dimensionId + ":" + change.pos().x() + ":" + change.pos().y() + ":" + change.pos().z(), actionId);
    }

    private void markOwner(String projectId, String actionId, String dimensionId, StoredEntityChange change) {
        if (projectId == null || projectId.isBlank() || actionId == null || actionId.isBlank()
                || change == null || change.entityId() == null || change.entityId().isBlank()) {
            return;
        }
        this.projectKeyOwners.computeIfAbsent(projectId, ignored -> new HashMap<>())
                .put("e:" + dimensionId + ":" + change.entityId(), actionId);
    }

    private List<String> keys(UndoRedoAction action) {
        List<String> keys = new ArrayList<>();
        String dimensionId = action.dimensionId();
        for (StoredBlockChange change : action.redoChanges()) {
            if (change != null && change.pos() != null) {
                keys.add("b:" + dimensionId + ":" + change.pos().x() + ":" + change.pos().y() + ":" + change.pos().z());
            }
        }
        for (StoredEntityChange change : action.redoEntityChanges()) {
            if (change != null && change.entityId() != null && !change.entityId().isBlank()) {
                keys.add("e:" + dimensionId + ":" + change.entityId());
            }
        }
        return keys;
    }

    private String actorKey(String actor) {
        return actor == null || actor.isBlank() ? "player" : actor;
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
