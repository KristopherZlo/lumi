package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.EntityState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Session-only action history for one dimension. */
public final class LiveActionJournal {
    private final Map<UUID, MutableAction> actions = new HashMap<>();
    private final Map<UUID, UUID> openActions = new HashMap<>();
    private final Map<UUID, PlayerStacks> playerStacks = new HashMap<>();
    private final OwnershipIndex<BlockPosition> blockOwners = new OwnershipIndex<>();
    private final OwnershipIndex<UUID> entityOwners = new OwnershipIndex<>();
    private final Map<UUID, Long> playerBytes = new HashMap<>();
    private final Map<UUID, String> unavailableReasons = new HashMap<>();
    private final Set<UUID> unsafeGroups = new HashSet<>();
    private final Limits limits;
    private long nextSequence;
    private long retainedBytes;
    private long unsafeBeforeSequence;

    public LiveActionJournal() {
        this(Limits.DEFAULT);
    }

    public LiveActionJournal(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public synchronized UUID begin(UUID player) {
        Objects.requireNonNull(player, "player");
        if (openActions.containsKey(player)) {
            throw new IllegalStateException("Player already has an open live action");
        }
        unavailableReasons.remove(player);
        UUID id = UUID.randomUUID();
        MutableAction action = new MutableAction(id, player, ++nextSequence);
        actions.put(id, action);
        openActions.put(player, id);
        return id;
    }

    public synchronized UUID record(
            UUID actionId,
            BlockPosition position,
            BlockSnapshot before,
            BlockSnapshot after) {
        MutableAction causalAction = requireAction(actionId);
        if (!causalAction.available) {
            return causalAction.id;
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        MutableAction action = effectiveAction(
                causalAction, position, blockOwners);
        startRecording(action);
        Change previous = action.changes.get(position);
        Change updated = new Change(previous == null ? before : previous.before, after);
        long previousBytes = previous == null ? 0 : estimatedBytes(previous);
        long updatedBytes = updated.before.equals(updated.after) ? 0 : estimatedBytes(updated);
        long delta = updatedBytes - previousBytes;
        if (!resize(action, delta)) {
            return action.id;
        }
        if (updatedBytes == 0) {
            action.changes.remove(position);
        } else {
            action.changes.put(position, updated);
        }
        blockOwners.set(position, action, updatedBytes != 0 && action.applied);
        touchClosedGroup(action);
        return action.id;
    }

    public synchronized UUID recordEntity(
            UUID actionId,
            UUID entityId,
            Optional<EntityState> before,
            Optional<EntityState> after) {
        MutableAction causalAction = requireAction(actionId);
        if (!causalAction.available) {
            return causalAction.id;
        }
        Objects.requireNonNull(entityId, "entityId");
        validateEntityId(entityId, before);
        validateEntityId(entityId, after);
        MutableAction action = effectiveAction(
                causalAction, entityId, entityOwners);
        startRecording(action);
        EntityChange previous = action.entities.get(entityId);
        EntityChange updated = new EntityChange(
                previous == null ? before : previous.before, after);
        long previousBytes = previous == null ? 0 : estimatedBytes(previous);
        long updatedBytes = updated.before.equals(updated.after) ? 0 : estimatedBytes(updated);
        if (!resize(action, updatedBytes - previousBytes)) {
            return action.id;
        }
        if (updatedBytes == 0) {
            action.entities.remove(entityId);
        } else {
            action.entities.put(entityId, updated);
        }
        entityOwners.set(entityId, action, updatedBytes != 0 && action.applied);
        touchClosedGroup(action);
        return action.id;
    }

    /** Adds one constant-size durable Restore pair to the session stack. */
    public synchronized UUID pushCheckpoint(UUID player, Checkpoint checkpoint) {
        UUID actionId = begin(player);
        MutableAction action = requireAction(actionId);
        startRecording(action);
        action.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        close(actionId);
        return actionId;
    }

    /** Joins causally inseparable actions into one session Undo/Redo entry. */
    public synchronized void mergeGroups(UUID firstAction, UUID secondAction) {
        MutableAction first = requireAction(firstAction);
        MutableAction second = requireAction(secondAction);
        if (first.checkpoint != null || second.checkpoint != null) {
            throw new IllegalArgumentException(
                    "Checkpoint actions cannot join causal groups");
        }
        if (!first.player.equals(second.player)) {
            throw new IllegalArgumentException(
                    "Live action groups cannot cross players");
        }
        if (first.applied != second.applied) {
            throw new IllegalStateException(
                    "Live action groups must share their applied state");
        }
        if (!first.available || !second.available) {
            return;
        }
        UUID source = second.groupId;
        if (!first.groupId.equals(source)) {
            if (unsafeGroups.remove(source)) {
                unsafeGroups.add(first.groupId);
            }
            actions.values().stream()
                    .filter(action -> action.groupId.equals(source))
                    .forEach(action -> action.groupId = first.groupId);
        }
        touchClosedGroup(first);
    }

    public synchronized void refreshEntityBefore(
            UUID actionId,
            UUID entityId,
            Optional<EntityState> before) {
        MutableAction selected = requireAction(actionId);
        MutableAction action = actions.values().stream()
                .filter(candidate -> candidate.groupId.equals(selected.groupId)
                        && candidate.entities.containsKey(entityId))
                .min(java.util.Comparator.comparingLong(candidate -> candidate.sequence))
                .orElseThrow(() -> new IllegalStateException(
                        "Entity is not part of the live action group: " + entityId));
        if (!action.available) {
            return;
        }
        if (action.applied) {
            throw new IllegalStateException("Cannot refresh before-state of an applied action");
        }
        Objects.requireNonNull(entityId, "entityId");
        validateEntityId(entityId, before);
        EntityChange previous = action.entities.get(entityId);
        EntityChange updated = new EntityChange(before, previous.after);
        long previousBytes = estimatedBytes(previous);
        long updatedBytes = updated.before.equals(updated.after) ? 0 : estimatedBytes(updated);
        if (!resize(action, updatedBytes - previousBytes)) {
            return;
        }
        if (updatedBytes == 0) {
            action.entities.remove(entityId);
        } else {
            action.entities.put(entityId, updated);
        }
        entityOwners.set(entityId, action, false);
    }

    public synchronized boolean close(UUID actionId) {
        MutableAction action = requireAction(actionId);
        if (action.closed) {
            return action.available && (!isEmpty(action) || action.causalReferences > 0);
        }
        action.closed = true;
        openActions.remove(action.player, action.id);
        if (!action.available) {
            evict(action);
            return false;
        }
        if (isEmpty(action) && action.causalReferences == 0) {
            evict(action, false);
            return false;
        }
        stacks(action.player).undo.addLast(action.id);
        while (stackSize(action.player) > limits.maxActionsPerPlayer) {
            evict(oldestClosed(action.player, action.id).orElseThrow());
        }
        return true;
    }

    public synchronized void retain(UUID actionId) {
        requireAction(actionId).causalReferences++;
    }

    public synchronized void release(UUID actionId) {
        MutableAction action = actions.get(Objects.requireNonNull(actionId, "actionId"));
        if (action == null) {
            return;
        }
        if (action.causalReferences < 1) {
            throw new IllegalStateException("Live action has no causal reference to release");
        }
        action.causalReferences--;
        if (action.closed && action.causalReferences == 0
                && (!action.available || isEmpty(action))) {
            evict(action, false);
        }
    }

    public synchronized Optional<Plan> prepareUndo(UUID player) {
        return prepare(player, Direction.UNDO, stacks(player).undo);
    }

    public synchronized Optional<Plan> prepareRedo(UUID player) {
        return prepare(player, Direction.REDO, stacks(player).redo);
    }

    public synchronized void complete(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        PlayerStacks stacks = stacks(plan.player);
        Deque<UUID> source = plan.direction == Direction.UNDO ? stacks.undo : stacks.redo;
        Deque<UUID> target = plan.direction == Direction.UNDO ? stacks.redo : stacks.undo;
        if (source.size() < plan.actionIds.size()) {
            throw new IllegalStateException("Live action stack changed during apply");
        }
        List<UUID> suffix = source.stream()
                .skip(source.size() - plan.actionIds.size()).toList();
        if (!suffix.equals(plan.actionIds)) {
            throw new IllegalStateException("Live action stack changed during apply");
        }
        for (UUID actionId : plan.actionIds) {
            MutableAction action = requireAction(actionId);
            action.applied = plan.direction == Direction.REDO;
            action.changes.keySet().forEach(position ->
                    blockOwners.set(position, action, action.applied));
            action.entities.keySet().forEach(entity ->
                    entityOwners.set(entity, action, action.applied));
        }
        for (int index = 0; index < plan.actionIds.size(); index++) {
            source.removeLast();
        }
        plan.actionIds.forEach(target::addLast);
    }

    public synchronized void clear() {
        actions.clear();
        openActions.clear();
        playerStacks.clear();
        blockOwners.clear();
        entityOwners.clear();
        playerBytes.clear();
        unavailableReasons.clear();
        unsafeGroups.clear();
        retainedBytes = 0;
        unsafeBeforeSequence = 0;
    }

    public synchronized Optional<String> lastUnavailableReason(UUID player) {
        return Optional.ofNullable(unavailableReasons.get(Objects.requireNonNull(player, "player")));
    }

    public synchronized Optional<UUID> owner(UUID actionId) {
        MutableAction action = actions.get(Objects.requireNonNull(actionId, "actionId"));
        return action == null ? Optional.empty() : Optional.of(action.player);
    }

    public synchronized ActionSummary summary(UUID actionId) {
        MutableAction action = requireAction(actionId);
        return new ActionSummary(
                action.id, action.player, action.changes.size(), action.entities.size(),
                action.bytes, action.causalReferences);
    }

    public synchronized void makeUnavailable(UUID actionId, String reason) {
        MutableAction action = requireAction(actionId);
        if (action.closed) {
            throw new IllegalStateException("Cannot disable a closed live action");
        }
        if (Objects.requireNonNull(reason, "reason").isBlank()) {
            throw new IllegalArgumentException("Unavailable reason cannot be blank");
        }
        makeUnavailable(action, reason);
    }

    private Optional<Plan> prepare(UUID player, Direction direction, Deque<UUID> stack) {
        Objects.requireNonNull(player, "player");
        UUID actionId = stack.peekLast();
        if (actionId == null) {
            return Optional.empty();
        }
        MutableAction selected = requireAction(actionId);
        if (unsafeGroups.contains(selected.groupId)) {
            throw new IllegalStateException(
                    "An evicted member makes this live action group unsafe");
        }
        List<MutableAction> group = stack.stream()
                .map(this::requireAction)
                .filter(action -> action.groupId.equals(selected.groupId))
                .toList();
        if (selected.checkpoint != null) {
            if (group.size() != 1) {
                throw new IllegalStateException(
                        "Checkpoint action cannot have group members");
            }
            return Optional.of(new Plan(
                    player, List.of(selected.id), direction,
                    Map.of(), Map.of(), Map.of(), Map.of(),
                    Optional.of(selected.checkpoint)));
        }
        if (group.stream().anyMatch(action ->
                action.sequence <= unsafeBeforeSequence)) {
            throw new IllegalStateException("A newer evicted action makes this live action unsafe");
        }
        Set<UUID> groupIds = group.stream()
                .map(action -> action.id)
                .collect(java.util.stream.Collectors.toSet());
        for (MutableAction action : group) {
            for (BlockPosition position : action.changes.keySet()) {
                if (overlaps(
                        action, direction, blockOwners.latest(position), groupIds)) {
                    throw new IllegalStateException(
                            "A newer action overlaps the selected live action");
                }
            }
            for (UUID entity : action.entities.keySet()) {
                if (overlaps(
                        action, direction, entityOwners.latest(entity), groupIds)) {
                    throw new IllegalStateException(
                            "A newer action overlaps the selected live action");
                }
            }
        }
        Map<BlockPosition, Change> blocks = new LinkedHashMap<>();
        Map<UUID, EntityChange> entities = new LinkedHashMap<>();
        for (MutableAction action : group) {
            action.changes.forEach((position, change) -> blocks.merge(
                    position, change,
                    (previous, latest) ->
                            new Change(previous.before, latest.after)));
            action.entities.forEach((entity, change) -> entities.merge(
                    entity, change,
                    (previous, latest) ->
                            new EntityChange(previous.before, latest.after)));
        }
        Map<BlockPosition, BlockSnapshot> expected = new LinkedHashMap<>();
        Map<BlockPosition, BlockSnapshot> replacement = new LinkedHashMap<>();
        Map<UUID, Optional<EntityState>> expectedEntities = new LinkedHashMap<>();
        Map<UUID, Optional<EntityState>> replacementEntities = new LinkedHashMap<>();
        blocks.forEach((position, change) -> {
            expected.put(position, direction == Direction.UNDO ? change.after : change.before);
            replacement.put(position, direction == Direction.UNDO ? change.before : change.after);
        });
        entities.forEach((id, change) -> {
            expectedEntities.put(id, direction == Direction.UNDO ? change.after : change.before);
            replacementEntities.put(id, direction == Direction.UNDO ? change.before : change.after);
        });
        return Optional.of(new Plan(
                player, group.stream().map(action -> action.id).toList(),
                direction, expected, replacement,
                expectedEntities, replacementEntities, Optional.empty()));
    }

    private MutableAction requireAction(UUID id) {
        MutableAction action = actions.get(Objects.requireNonNull(id, "actionId"));
        if (action == null) {
            throw new IllegalArgumentException("Unknown live action: " + id);
        }
        return action;
    }

    private <K> MutableAction effectiveAction(
            MutableAction causalAction, K key, OwnershipIndex<K> owners) {
        if (!causalAction.closed) {
            return causalAction;
        }
        return owners.latest(key)
                .filter(owner -> owner.sequence > causalAction.sequence)
                .map(owner -> requireAction(owner.actionId))
                .filter(owner -> owner.player.equals(causalAction.player))
                .orElse(causalAction);
    }

    private void touchClosedGroup(MutableAction action) {
        if (!action.closed || !action.applied) {
            return;
        }
        PlayerStacks stacks = stacks(action.player);
        List<UUID> members = actions.values().stream()
                .filter(candidate -> candidate.available && candidate.closed
                        && candidate.applied
                        && candidate.groupId.equals(action.groupId))
                .sorted(java.util.Comparator.comparingLong(
                        candidate -> candidate.sequence))
                .map(candidate -> candidate.id)
                .toList();
        stacks.undo.removeAll(members);
        members.forEach(stacks.undo::addLast);
    }

    private static boolean overlaps(
            MutableAction action,
            Direction direction,
            Optional<Ownership> latest,
            Set<UUID> group) {
        if (direction == Direction.UNDO) {
            return latest.isEmpty()
                    || !group.contains(latest.orElseThrow().actionId);
        }
        return latest.filter(owner -> !group.contains(owner.actionId)
                && owner.sequence > action.sequence).isPresent();
    }

    private void startRecording(MutableAction action) {
        if (!action.started) {
            action.started = true;
            clearRedo(action.player);
        }
    }

    private boolean resize(MutableAction action, long delta) {
        if (action.bytes + delta > limits.maxActionBytes
                || (delta > 0 && !makeRoom(action, delta))) {
            makeUnavailable(action, "Live action exceeded its memory limit");
            return false;
        }
        action.bytes += delta;
        adjustBytes(action.player, delta);
        return true;
    }

    private static void validateEntityId(UUID id, Optional<EntityState> snapshot) {
        Objects.requireNonNull(snapshot, "entity snapshot").ifPresent(state -> {
            if (!state.id().equals(id)) {
                throw new IllegalArgumentException("Entity snapshot UUID does not match its key");
            }
        });
    }

    private static boolean isEmpty(MutableAction action) {
        return action.changes.isEmpty() && action.entities.isEmpty()
                && action.checkpoint == null;
    }

    private PlayerStacks stacks(UUID player) {
        return playerStacks.computeIfAbsent(Objects.requireNonNull(player, "player"), ignored -> new PlayerStacks());
    }

    private void clearRedo(UUID player) {
        for (UUID id : java.util.List.copyOf(stacks(player).redo)) {
            MutableAction action = actions.get(id);
            if (action != null) {
                evict(action, false);
            }
        }
    }

    private boolean makeRoom(MutableAction current, long addedBytes) {
        while (playerBytes.getOrDefault(current.player, 0L) + addedBytes
                > limits.maxPlayerBytes) {
            Optional<MutableAction> oldest = oldestClosed(current.player, current.id);
            if (oldest.isEmpty()) {
                return false;
            }
            evict(oldest.orElseThrow());
        }
        while (retainedBytes + addedBytes > limits.maxDimensionBytes) {
            Optional<MutableAction> oldest = actions.values().stream()
                    .filter(action -> action.closed && action.available
                            && !action.id.equals(current.id))
                    .min(java.util.Comparator.comparingLong(action -> action.sequence));
            if (oldest.isEmpty()) {
                return false;
            }
            evict(oldest.orElseThrow());
        }
        return true;
    }

    private Optional<MutableAction> oldestClosed(UUID player, UUID excluded) {
        return actions.values().stream()
                .filter(action -> action.player.equals(player) && action.closed
                        && action.available && !action.id.equals(excluded))
                .min(java.util.Comparator.comparingLong(action -> action.sequence));
    }

    private int stackSize(UUID player) {
        PlayerStacks stacks = stacks(player);
        return stacks.undo.size() + stacks.redo.size();
    }

    private void makeUnavailable(MutableAction action, String reason) {
        action.available = false;
        unavailableReasons.put(action.player, reason);
        evictState(action);
        if (action.closed && action.causalReferences == 0) {
            actions.remove(action.id);
        }
    }

    private void evict(MutableAction action) {
        evict(action, true);
    }

    private void evict(MutableAction action, boolean createsSequenceBarrier) {
        evictState(action, createsSequenceBarrier);
        action.available = false;
        if (action.causalReferences == 0) {
            actions.remove(action.id);
        }
    }

    private void evictState(MutableAction action) {
        evictState(action, true);
    }

    private void evictState(MutableAction action, boolean createsSequenceBarrier) {
        adjustBytes(action.player, -action.bytes);
        action.bytes = 0;
        action.changes.keySet().forEach(position -> blockOwners.set(position, action, false));
        action.entities.keySet().forEach(entity -> entityOwners.set(entity, action, false));
        action.changes.clear();
        action.entities.clear();
        PlayerStacks stacks = stacks(action.player);
        stacks.undo.remove(action.id);
        stacks.redo.remove(action.id);
        if (createsSequenceBarrier) {
            unsafeBeforeSequence = Math.max(unsafeBeforeSequence, action.sequence);
            unsafeGroups.add(action.groupId);
        }
    }

    private void adjustBytes(UUID player, long delta) {
        retainedBytes += delta;
        long updated = playerBytes.getOrDefault(player, 0L) + delta;
        if (updated == 0) {
            playerBytes.remove(player);
        } else {
            playerBytes.put(player, updated);
        }
    }

    private static long estimatedBytes(Change change) {
        return 24L + estimatedBytes(change.before) + estimatedBytes(change.after);
    }

    private static long estimatedBytes(BlockSnapshot snapshot) {
        return snapshot.blockState().getBytes(StandardCharsets.UTF_8).length
                + snapshot.blockEntity().map(nbt -> nbt.bytes().length).orElse(0);
    }

    private static long estimatedBytes(EntityChange change) {
        return 24L + change.before.map(LiveActionJournal::estimatedBytes).orElse(0L)
                + change.after.map(LiveActionJournal::estimatedBytes).orElse(0L);
    }

    private static long estimatedBytes(EntityState state) {
        return 24L + state.type().getBytes(StandardCharsets.UTF_8).length + state.nbt().bytes().length;
    }

    public enum Direction {
        UNDO,
        REDO
    }

    public record Limits(
            int maxActionsPerPlayer,
            long maxActionBytes,
            long maxPlayerBytes,
            long maxDimensionBytes) {
        public static final Limits DEFAULT = new Limits(
                64, 64L << 20, 128L << 20, 256L << 20);

        public Limits {
            if (maxActionsPerPlayer < 1 || maxActionBytes < 1
                    || maxPlayerBytes < 1 || maxDimensionBytes < 1) {
                throw new IllegalArgumentException("Live action limits must be positive");
            }
        }
    }

    public record Plan(
            UUID player,
            List<UUID> actionIds,
            Direction direction,
            Map<BlockPosition, BlockSnapshot> expected,
            Map<BlockPosition, BlockSnapshot> replacement,
            Map<UUID, Optional<EntityState>> expectedEntities,
            Map<UUID, Optional<EntityState>> replacementEntities,
            Optional<Checkpoint> checkpoint) {
        public Plan {
            Objects.requireNonNull(player, "player");
            actionIds = List.copyOf(Objects.requireNonNull(
                    actionIds, "actionIds"));
            if (actionIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Live action plan cannot be empty");
            }
            Objects.requireNonNull(direction, "direction");
            expected = immutableOrderedCopy(expected);
            replacement = immutableOrderedCopy(replacement);
            expectedEntities = immutableOrderedCopy(expectedEntities);
            replacementEntities = immutableOrderedCopy(replacementEntities);
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        }

        public UUID actionId() {
            return actionIds.getLast();
        }

        private static <K, V> Map<K, V> immutableOrderedCopy(Map<K, V> source) {
            var copy = new LinkedHashMap<K, V>();
            Objects.requireNonNull(source, "source").forEach((key, value) -> copy.put(
                    Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, "value")));
            return Collections.unmodifiableMap(copy);
        }
    }

    public record ActionSummary(
            UUID actionId,
            UUID player,
            int blocks,
            int entities,
            long bytes,
            int delayedReferences) { }

    public record Checkpoint(
            BranchRef expectedRef,
            CommitId dirtyCommit,
            BranchName hiddenRef) {
        public Checkpoint {
            Objects.requireNonNull(expectedRef, "expectedRef");
            Objects.requireNonNull(dirtyCommit, "dirtyCommit");
            Objects.requireNonNull(hiddenRef, "hiddenRef");
        }
    }

    private static final class MutableAction {
        private final UUID id;
        private final UUID player;
        private final long sequence;
        private UUID groupId;
        private final Map<BlockPosition, Change> changes = new LinkedHashMap<>();
        private final Map<UUID, EntityChange> entities = new LinkedHashMap<>();
        private boolean closed;
        private boolean started;
        private boolean available = true;
        private boolean applied = true;
        private Checkpoint checkpoint;
        private long bytes;
        private int causalReferences;

        private MutableAction(UUID id, UUID player, long sequence) {
            this.id = id;
            this.player = player;
            this.sequence = sequence;
            groupId = id;
        }
    }

    private record Change(BlockSnapshot before, BlockSnapshot after) {}

    private record EntityChange(Optional<EntityState> before, Optional<EntityState> after) {}

    private record Ownership(UUID actionId, long sequence) {}

    private static final class OwnershipIndex<K> {
        private final Map<K, NavigableMap<Long, UUID>> owners = new HashMap<>();

        private void set(K key, MutableAction action, boolean included) {
            if (included) {
                owners.computeIfAbsent(key, ignored -> new TreeMap<>())
                        .put(action.sequence, action.id);
                return;
            }
            NavigableMap<Long, UUID> indexed = owners.get(key);
            if (indexed == null) {
                return;
            }
            indexed.remove(action.sequence);
            if (indexed.isEmpty()) {
                owners.remove(key);
            }
        }

        private Optional<Ownership> latest(K key) {
            NavigableMap<Long, UUID> indexed = owners.get(key);
            if (indexed == null || indexed.isEmpty()) {
                return Optional.empty();
            }
            Map.Entry<Long, UUID> latest = indexed.lastEntry();
            return Optional.of(new Ownership(latest.getValue(), latest.getKey()));
        }

        private void clear() {
            owners.clear();
        }
    }

    private static final class PlayerStacks {
        private final Deque<UUID> undo = new ArrayDeque<>();
        private final Deque<UUID> redo = new ArrayDeque<>();
    }
}
