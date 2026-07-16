package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.EntityState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Session-only action history for one dimension. */
public final class LiveActionJournal {
    private final Map<UUID, MutableAction> actions = new HashMap<>();
    private final Map<UUID, UUID> openActions = new HashMap<>();
    private final Map<UUID, PlayerStacks> playerStacks = new HashMap<>();
    private final Map<BlockPosition, Ownership> latestOwners = new HashMap<>();
    private final Map<UUID, Ownership> latestEntityOwners = new HashMap<>();
    private final Map<UUID, Long> playerBytes = new HashMap<>();
    private final Map<UUID, String> unavailableReasons = new HashMap<>();
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

    public synchronized void record(
            UUID actionId,
            BlockPosition position,
            BlockSnapshot before,
            BlockSnapshot after) {
        MutableAction action = requireAction(actionId);
        if (!action.available) {
            return;
        }
        startRecording(action);
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Change previous = action.changes.get(position);
        Change updated = new Change(previous == null ? before : previous.before, after);
        long previousBytes = previous == null ? 0 : estimatedBytes(previous);
        long updatedBytes = updated.before.equals(updated.after) ? 0 : estimatedBytes(updated);
        long delta = updatedBytes - previousBytes;
        if (!resize(action, delta)) {
            return;
        }
        if (updatedBytes == 0) {
            action.changes.remove(position);
        } else {
            action.changes.put(position, updated);
        }
        latestOwners.compute(position, (ignored, current) ->
                current == null || current.sequence <= action.sequence
                        ? new Ownership(action.id, action.sequence)
                        : current);
    }

    public synchronized void recordEntity(
            UUID actionId,
            UUID entityId,
            Optional<EntityState> before,
            Optional<EntityState> after) {
        MutableAction action = requireAction(actionId);
        if (!action.available) {
            return;
        }
        startRecording(action);
        Objects.requireNonNull(entityId, "entityId");
        validateEntityId(entityId, before);
        validateEntityId(entityId, after);
        EntityChange previous = action.entities.get(entityId);
        EntityChange updated = new EntityChange(
                previous == null ? before : previous.before, after);
        long previousBytes = previous == null ? 0 : estimatedBytes(previous);
        long updatedBytes = updated.before.equals(updated.after) ? 0 : estimatedBytes(updated);
        if (!resize(action, updatedBytes - previousBytes)) {
            return;
        }
        if (updatedBytes == 0) {
            action.entities.remove(entityId);
        } else {
            action.entities.put(entityId, updated);
        }
        latestEntityOwners.compute(entityId, (ignored, current) ->
                current == null || current.sequence <= action.sequence
                        ? new Ownership(action.id, action.sequence) : current);
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
        if (action.closed && action.causalReferences == 0 && isEmpty(action)) {
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
        if (!plan.actionId.equals(source.peekLast())) {
            throw new IllegalStateException("Live action stack changed during apply");
        }
        source.removeLast();
        target.addLast(plan.actionId);
    }

    public synchronized void clear() {
        actions.clear();
        openActions.clear();
        playerStacks.clear();
        latestOwners.clear();
        latestEntityOwners.clear();
        playerBytes.clear();
        unavailableReasons.clear();
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
        MutableAction action = requireAction(actionId);
        if (action.sequence <= unsafeBeforeSequence) {
            throw new IllegalStateException("A newer evicted action makes this live action unsafe");
        }
        for (BlockPosition position : action.changes.keySet()) {
            Ownership owner = latestOwners.get(position);
            if (owner == null || !owner.actionId.equals(actionId)) {
                throw new IllegalStateException("A newer action overlaps the selected live action");
            }
        }
        for (UUID entity : action.entities.keySet()) {
            Ownership owner = latestEntityOwners.get(entity);
            if (owner == null || !owner.actionId.equals(actionId)) {
                throw new IllegalStateException("A newer action overlaps the selected live action");
            }
        }
        Map<BlockPosition, BlockSnapshot> expected = new LinkedHashMap<>();
        Map<BlockPosition, BlockSnapshot> replacement = new LinkedHashMap<>();
        Map<UUID, Optional<EntityState>> expectedEntities = new LinkedHashMap<>();
        Map<UUID, Optional<EntityState>> replacementEntities = new LinkedHashMap<>();
        action.changes.forEach((position, change) -> {
            expected.put(position, direction == Direction.UNDO ? change.after : change.before);
            replacement.put(position, direction == Direction.UNDO ? change.before : change.after);
        });
        action.entities.forEach((id, change) -> {
            expectedEntities.put(id, direction == Direction.UNDO ? change.after : change.before);
            replacementEntities.put(id, direction == Direction.UNDO ? change.before : change.after);
        });
        return Optional.of(new Plan(
                player, actionId, direction, expected, replacement,
                expectedEntities, replacementEntities));
    }

    private MutableAction requireAction(UUID id) {
        MutableAction action = actions.get(Objects.requireNonNull(id, "actionId"));
        if (action == null) {
            throw new IllegalArgumentException("Unknown live action: " + id);
        }
        return action;
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
        return action.changes.isEmpty() && action.entities.isEmpty();
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
        if (action.closed) {
            actions.remove(action.id);
        }
    }

    private void evict(MutableAction action) {
        evict(action, true);
    }

    private void evict(MutableAction action, boolean createsSequenceBarrier) {
        evictState(action, createsSequenceBarrier);
        actions.remove(action.id);
    }

    private void evictState(MutableAction action) {
        evictState(action, true);
    }

    private void evictState(MutableAction action, boolean createsSequenceBarrier) {
        adjustBytes(action.player, -action.bytes);
        action.bytes = 0;
        action.changes.clear();
        action.entities.clear();
        PlayerStacks stacks = stacks(action.player);
        stacks.undo.remove(action.id);
        stacks.redo.remove(action.id);
        if (createsSequenceBarrier) {
            unsafeBeforeSequence = Math.max(unsafeBeforeSequence, action.sequence);
        }
        latestOwners.entrySet().removeIf(entry -> entry.getValue().actionId.equals(action.id));
        latestEntityOwners.entrySet().removeIf(entry -> entry.getValue().actionId.equals(action.id));
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
            UUID actionId,
            Direction direction,
            Map<BlockPosition, BlockSnapshot> expected,
            Map<BlockPosition, BlockSnapshot> replacement,
            Map<UUID, Optional<EntityState>> expectedEntities,
            Map<UUID, Optional<EntityState>> replacementEntities) {
        public Plan {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(direction, "direction");
            expected = Map.copyOf(expected);
            replacement = Map.copyOf(replacement);
            expectedEntities = Map.copyOf(expectedEntities);
            replacementEntities = Map.copyOf(replacementEntities);
        }
    }

    private static final class MutableAction {
        private final UUID id;
        private final UUID player;
        private final long sequence;
        private final Map<BlockPosition, Change> changes = new LinkedHashMap<>();
        private final Map<UUID, EntityChange> entities = new LinkedHashMap<>();
        private boolean closed;
        private boolean started;
        private boolean available = true;
        private long bytes;
        private int causalReferences;

        private MutableAction(UUID id, UUID player, long sequence) {
            this.id = id;
            this.player = player;
            this.sequence = sequence;
        }
    }

    private record Change(BlockSnapshot before, BlockSnapshot after) {}

    private record EntityChange(Optional<EntityState> before, Optional<EntityState> after) {}

    private record Ownership(UUID actionId, long sequence) {}

    private static final class PlayerStacks {
        private final Deque<UUID> undo = new ArrayDeque<>();
        private final Deque<UUID> redo = new ArrayDeque<>();
    }
}
