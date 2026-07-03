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
    private final Map<String, Map<String, KeyOwner>> projectKeyOwners = new HashMap<>();
    private final Map<String, Long> projectRevisions = new HashMap<>();

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
        long revision = stack.revision();
        stack.recordChange(actionId, actor, projectId, dimensionId, change, now);
        this.finishStackMutation(projectId, stack, revision);
    }

    public synchronized void recordRelatedChange(
            String projectId,
            String dimensionId,
            String actor,
            StoredBlockChange change,
            Instant now,
            Duration maxIdle,
            int chunkRadius
    ) {
        if (projectId == null || projectId.isBlank() || actor == null || actor.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stack(projectId, actor, false);
        if (stack == null) {
            return;
        }
        long revision = stack.revision();
        stack.recordRelatedChange(dimensionId, change, now, maxIdle, chunkRadius);
        this.finishStackMutation(projectId, stack, revision);
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
        long revision = stack.revision();
        stack.recordCausalChange(actionId, change, now);
        this.finishStackMutation(projectId, stack, revision);
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
        long revision = stack.revision();
        stack.recordCurrentCausalChange(actionId, actor, projectId, dimensionId, change, now);
        this.finishStackMutation(projectId, stack, revision);
    }

    public synchronized void recordRelatedEntityChange(
            String projectId,
            String dimensionId,
            String actor,
            StoredEntityChange change,
            Instant now,
            Duration maxIdle,
            int chunkRadius
    ) {
        if (projectId == null || projectId.isBlank() || actor == null || actor.isBlank()) {
            return;
        }
        UndoRedoActionStack stack = this.stack(projectId, actor, false);
        if (stack == null) {
            return;
        }
        long revision = stack.revision();
        stack.recordRelatedEntityChange(dimensionId, change, now, maxIdle, chunkRadius);
        this.finishStackMutation(projectId, stack, revision);
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
        long revision = stack.revision();
        stack.recordCausalEntityChange(actionId, change, now);
        this.finishStackMutation(projectId, stack, revision);
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
        long revision = stack.revision();
        stack.recordEntityChange(actionId, actor, projectId, dimensionId, change, now);
        this.finishStackMutation(projectId, stack, revision);
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
        long revision = stack.revision();
        stack.recordDelayedEntityChange(
                actionId,
                actor,
                projectId,
                dimensionId,
                change,
                actionStartedAt,
                now
        );
        this.finishStackMutation(projectId, stack, revision);
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
        long revision = stack.revision();
        stack.recordDelayedEntityChanges(
                actionId,
                actor,
                projectId,
                dimensionId,
                changes,
                actionStartedAt,
                now
        );
        this.finishStackMutation(projectId, stack, revision);
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
        long revision = stack.revision();
        stack.recordAction(actionId, actor, projectId, dimensionId, changes, entityChanges, now);
        this.finishStackMutation(projectId, stack, revision);
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
        long revision = stack.revision();
        stack.recordCausalAction(actionId, changes, entityChanges, now);
        this.finishStackMutation(projectId, stack, revision);
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
        long revision = stack.revision();
        stack.recordCurrentCausalAction(
                actionId,
                actor,
                projectId,
                dimensionId,
                changes,
                entityChanges,
                now
        );
        this.finishStackMutation(projectId, stack, revision);
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
        return this.canRedo(projectId, selection) ? selection : null;
    }

    public synchronized boolean completeUndo(String projectId, UndoRedoActionStack.Selection selection) {
        UndoRedoActionStack stack = this.stackForSelection(projectId, selection);
        if (stack != null) {
            long revision = stack.revision();
            boolean completed = stack.completeUndo(selection);
            this.finishStackMutation(projectId, stack, revision);
            return completed;
        }
        return false;
    }

    public synchronized boolean completeRedo(String projectId, UndoRedoActionStack.Selection selection) {
        UndoRedoActionStack stack = this.stackForSelection(projectId, selection);
        if (stack != null) {
            long revision = stack.revision();
            boolean completed = stack.completeRedo(selection);
            this.finishStackMutation(projectId, stack, revision);
            return completed;
        }
        return false;
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
        return this.projectRevisions.getOrDefault(projectId, 0L);
    }

    public synchronized void clearProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        this.projectStacks.remove(projectId);
        this.projectKeyOwners.remove(projectId);
        this.projectRevisions.remove(projectId);
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

    private void finishStackMutation(String projectId, UndoRedoActionStack stack, long revisionBefore) {
        if (stack == null || stack.revision() == revisionBefore) {
            return;
        }
        this.projectRevisions.merge(projectId, 1L, Long::sum);
        this.rebuildOwners(projectId);
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
                .filter(selection -> undo ? this.isCurrent(projectId, selection) : this.canRedo(projectId, selection))
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
                .filter(action -> undo ? this.actionIsCurrent(projectId, action) : this.actionCanRedo(projectId, action))
                .sorted(Comparator.comparing(UndoRedoAction::updatedAt).reversed())
                .limit(count)
                .toList();
    }

    private boolean isCurrent(String projectId, UndoRedoActionStack.Selection selection) {
        return selection != null && this.actionIsCurrent(projectId, selection.action());
    }

    private boolean canRedo(String projectId, UndoRedoActionStack.Selection selection) {
        return selection != null && this.actionCanRedo(projectId, selection.action());
    }

    private boolean actionIsCurrent(String projectId, UndoRedoAction action) {
        if (action == null) {
            return false;
        }
        Map<String, KeyOwner> owners = this.projectKeyOwners.get(projectId);
        if (owners == null || owners.isEmpty()) {
            return true;
        }
        for (String key : this.keys(action)) {
            KeyOwner owner = owners.get(key);
            if (owner != null && !owner.actionId().equals(action.id())) {
                return false;
            }
        }
        return true;
    }

    private boolean actionCanRedo(String projectId, UndoRedoAction action) {
        if (action == null) {
            return false;
        }
        Map<String, KeyOwner> owners = this.projectKeyOwners.get(projectId);
        if (owners == null || owners.isEmpty()) {
            return true;
        }
        for (String key : this.keys(action)) {
            KeyOwner owner = owners.get(key);
            if (owner != null && owner.updatedAt().isAfter(action.updatedAt())) {
                return false;
            }
        }
        return true;
    }

    private void rebuildOwners(String projectId) {
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null || stacks.isEmpty()) {
            this.projectKeyOwners.remove(projectId);
            return;
        }

        Map<String, KeyOwner> owners = new HashMap<>();
        stacks.values().stream()
                .flatMap(stack -> stack.recentUndoActions(64).stream())
                .sorted(Comparator.comparing(UndoRedoAction::updatedAt))
                .forEach(action -> this.markOwners(owners, action));
        if (owners.isEmpty()) {
            this.projectKeyOwners.remove(projectId);
        } else {
            this.projectKeyOwners.put(projectId, owners);
        }
    }

    private void markOwners(Map<String, KeyOwner> owners, UndoRedoAction action) {
        if (owners == null || action == null || action.id() == null || action.id().isBlank()) {
            return;
        }
        for (String key : this.keys(action)) {
            owners.put(key, new KeyOwner(action.id(), action.updatedAt()));
        }
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

    private record KeyOwner(String actionId, Instant updatedAt) {
    }
}
