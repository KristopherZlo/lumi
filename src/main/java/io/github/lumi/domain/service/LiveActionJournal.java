package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
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
    private long nextSequence;

    public synchronized UUID begin(UUID player) {
        Objects.requireNonNull(player, "player");
        if (openActions.containsKey(player)) {
            throw new IllegalStateException("Player already has an open live action");
        }
        PlayerStacks stacks = stacks(player);
        stacks.redo.clear();
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
        action.record(position, before, after);
        latestOwners.compute(position, (ignored, current) ->
                current == null || current.sequence <= action.sequence
                        ? new Ownership(action.id, action.sequence)
                        : current);
    }

    public synchronized boolean close(UUID actionId) {
        MutableAction action = requireAction(actionId);
        if (action.closed) {
            return !action.changes.isEmpty();
        }
        action.closed = true;
        openActions.remove(action.player, action.id);
        if (action.changes.isEmpty()) {
            return false;
        }
        stacks(action.player).undo.addLast(action.id);
        return true;
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
    }

    private Optional<Plan> prepare(UUID player, Direction direction, Deque<UUID> stack) {
        Objects.requireNonNull(player, "player");
        UUID actionId = stack.peekLast();
        if (actionId == null) {
            return Optional.empty();
        }
        MutableAction action = requireAction(actionId);
        for (BlockPosition position : action.changes.keySet()) {
            Ownership owner = latestOwners.get(position);
            if (owner == null || !owner.actionId.equals(actionId)) {
                throw new IllegalStateException("A newer action overlaps the selected live action");
            }
        }
        Map<BlockPosition, BlockSnapshot> expected = new LinkedHashMap<>();
        Map<BlockPosition, BlockSnapshot> replacement = new LinkedHashMap<>();
        action.changes.forEach((position, change) -> {
            expected.put(position, direction == Direction.UNDO ? change.after : change.before);
            replacement.put(position, direction == Direction.UNDO ? change.before : change.after);
        });
        return Optional.of(new Plan(player, actionId, direction, expected, replacement));
    }

    private MutableAction requireAction(UUID id) {
        MutableAction action = actions.get(Objects.requireNonNull(id, "actionId"));
        if (action == null) {
            throw new IllegalArgumentException("Unknown live action: " + id);
        }
        return action;
    }

    private PlayerStacks stacks(UUID player) {
        return playerStacks.computeIfAbsent(Objects.requireNonNull(player, "player"), ignored -> new PlayerStacks());
    }

    public enum Direction {
        UNDO,
        REDO
    }

    public record Plan(
            UUID player,
            UUID actionId,
            Direction direction,
            Map<BlockPosition, BlockSnapshot> expected,
            Map<BlockPosition, BlockSnapshot> replacement) {
        public Plan {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(direction, "direction");
            expected = Map.copyOf(expected);
            replacement = Map.copyOf(replacement);
        }
    }

    private static final class MutableAction {
        private final UUID id;
        private final UUID player;
        private final long sequence;
        private final Map<BlockPosition, Change> changes = new LinkedHashMap<>();
        private boolean closed;

        private MutableAction(UUID id, UUID player, long sequence) {
            this.id = id;
            this.player = player;
            this.sequence = sequence;
        }

        private void record(BlockPosition position, BlockSnapshot before, BlockSnapshot after) {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            changes.compute(position, (ignored, existing) ->
                    existing == null ? new Change(before, after) : new Change(existing.before, after));
        }
    }

    private record Change(BlockSnapshot before, BlockSnapshot after) {}

    private record Ownership(UUID actionId, long sequence) {}

    private static final class PlayerStacks {
        private final Deque<UUID> undo = new ArrayDeque<>();
        private final Deque<UUID> redo = new ArrayDeque<>();
    }
}
