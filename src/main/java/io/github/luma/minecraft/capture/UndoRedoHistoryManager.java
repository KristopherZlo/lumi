package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.UndoRedoActionStack;
import java.util.ArrayList;
import java.util.Collection;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory per-project undo/redo history for live builder actions.
 */
public final class UndoRedoHistoryManager {

    private static final UndoRedoHistoryManager INSTANCE = new UndoRedoHistoryManager();

    private final Map<String, Map<String, UndoRedoActionStack>> projectStacks = new HashMap<>();
    private final Map<String, Long> projectRevisions = new HashMap<>();

    private UndoRedoHistoryManager() {
    }

    public static UndoRedoHistoryManager getInstance() {
        return INSTANCE;
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
        return this.latestSelection(projectId, this.actorKeys(actor), true);
    }

    public synchronized UndoRedoActionStack.Selection selectRedo(String projectId) {
        return this.latestSelection(projectId, false);
    }

    public synchronized UndoRedoActionStack.Selection selectRedo(String projectId, String actor) {
        return this.latestSelection(projectId, this.actorKeys(actor), false);
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

    public synchronized RecentActionsSnapshot recentUndoPreviewActionsSnapshot(
            String projectId,
            int count
    ) {
        if (projectId == null || projectId.isBlank()) {
            return new RecentActionsSnapshot(0L, List.of());
        }
        return new RecentActionsSnapshot(
                this.revision(projectId),
                this.recentPreviewActions(projectId, count, true)
        );
    }

    public synchronized RecentActionsSnapshot recentRedoPreviewActionsSnapshot(
            String projectId,
            int count
    ) {
        if (projectId == null || projectId.isBlank()) {
            return new RecentActionsSnapshot(0L, List.of());
        }
        return new RecentActionsSnapshot(
                this.revision(projectId),
                this.recentPreviewActions(projectId, count, false)
        );
    }

    public synchronized UndoRedoActionsSnapshot recentUndoRedoPreviewActionsSnapshot(
            String projectId,
            int count
    ) {
        if (projectId == null || projectId.isBlank()) {
            return new UndoRedoActionsSnapshot(0L, List.of(), List.of());
        }
        return new UndoRedoActionsSnapshot(
                this.revision(projectId),
                this.recentPreviewActions(projectId, count, true),
                this.recentPreviewActions(projectId, count, false)
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

    private void finishStackMutation(String projectId, UndoRedoActionStack stack, long revisionBefore) {
        if (stack == null || stack.revision() == revisionBefore) {
            return;
        }
        this.projectRevisions.merge(projectId, 1L, Long::sum);
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

    private UndoRedoActionStack.Selection latestSelection(String projectId, Collection<String> actorKeys, boolean undo) {
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null || stacks.isEmpty() || actorKeys == null || actorKeys.isEmpty()) {
            return null;
        }
        return actorKeys.stream()
                .map(stacks::get)
                .filter(stack -> stack != null)
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

    private List<UndoRedoAction> recentPreviewActions(
            String projectId,
            int count,
            boolean undo
    ) {
        if (count <= 0) {
            return List.of();
        }
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        List<UndoRedoAction> actions = new ArrayList<>();
        for (UndoRedoActionStack stack : stacks.values()) {
            actions.addAll(undo
                    ? stack.recentUndoActions(64)
                    : stack.recentRedoActions(64));
        }
        return actions.stream()
                .filter(action -> undo ? this.actionIsCurrent(projectId, action) : this.actionCanRedo(projectId, action))
                .sorted(Comparator.comparing(UndoRedoAction::updatedAt).reversed())
                .limit(count)
                .map(UndoRedoAction::copy)
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
        Map<String, KeyOwner> owners = this.currentOwners(projectId);
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
        Map<String, KeyOwner> owners = this.currentOwners(projectId);
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

    private Map<String, KeyOwner> currentOwners(String projectId) {
        Map<String, UndoRedoActionStack> stacks = this.projectStacks.get(projectId);
        if (stacks == null || stacks.isEmpty()) {
            return Map.of();
        }

        Map<String, KeyOwner> owners = new HashMap<>();
        stacks.values().stream()
                .flatMap(stack -> stack.recentUndoActions(64).stream())
                .sorted(Comparator.comparing(UndoRedoAction::updatedAt))
                .forEach(action -> this.markOwners(owners, action));
        return owners;
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

    private List<String> actorKeys(String actor) {
        String key = this.actorKey(actor);
        String owner = this.ownerActor(key);
        String player = owner.isBlank() ? key : owner;
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(key);
        if (!owner.isBlank()) {
            keys.add(owner);
        }
        keys.add("axiom:" + player);
        keys.add("worldedit:" + player);
        keys.add("worldedit:" + player.toLowerCase(Locale.ROOT));
        keys.add("fawe:" + player);
        keys.add("fawe:" + player.toLowerCase(Locale.ROOT));
        keys.add("external-tool:" + player);
        return List.copyOf(keys);
    }

    private String ownerActor(String actor) {
        int separator = actor == null ? -1 : actor.indexOf(':');
        return separator >= 0 && separator + 1 < actor.length() ? actor.substring(separator + 1) : "";
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
