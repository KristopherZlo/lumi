package io.github.lumi.domain.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;

/** One consistent order/cursor boundary over immutable action payloads. */
public record WorldActionIndex(
        long revision, long nextSequence, long unsafeBeforeSequence,
        List<WorldActionRecord.Metadata> actions, Map<UUID, Stacks> players) {
    public WorldActionIndex {
        if (revision < 0 || nextSequence < 0 || unsafeBeforeSequence < 0 || unsafeBeforeSequence > nextSequence) {
            throw new IllegalArgumentException("Invalid history boundary");
        }
        actions = List.copyOf(actions);
        players = Map.copyOf(players);
        var byId = new HashMap<UUID, WorldActionRecord.Metadata>();
        var groups = new HashMap<UUID, WorldActionRecord.Metadata>();
        long previous = 0;
        for (var action : actions) {
            if (action.sequence() <= previous || action.sequence() > nextSequence || byId.put(action.id(), action) != null) {
                throw new IllegalArgumentException("Invalid or duplicated action order");
            }
            previous = action.sequence();
            var group = groups.putIfAbsent(action.group(), action);
            if (group != null && (!group.actor().equals(action.actor()) || group.applied() != action.applied())) {
                throw new IllegalArgumentException("Action group crosses an actor or cursor boundary");
            }
        }
        var indexed = new HashSet<UUID>();
        players.forEach((player, stacks) -> {
            validateStack(player, stacks.undo(), true, byId, indexed);
            validateStack(player, stacks.redo(), false, byId, indexed);
        });
        for (var action : actions) {
            if (action.closed() != indexed.contains(action.id())) {
                throw new IllegalArgumentException("Action completion and cursor disagree");
            }
        }
    }

    private static void validateStack(UUID actor, List<UUID> stack, boolean applied,
            Map<UUID, WorldActionRecord.Metadata> actions, java.util.Set<UUID> indexed) {
        for (UUID id : stack) {
            var action = actions.get(id);
            if (action == null || !actor.equals(action.actor()) || action.applied() != applied || !indexed.add(id)) {
                throw new IllegalArgumentException("Invalid action cursor");
            }
        }
    }

    public record Stacks(List<UUID> undo, List<UUID> redo) {
        public Stacks {
            undo = List.copyOf(Objects.requireNonNull(undo, "undo"));
            redo = List.copyOf(Objects.requireNonNull(redo, "redo"));
        }
    }

    /** Projects one whole group; publication can retry without moving the cursor twice. */
    public WorldActionIndex move(ActionReplayTarget step, boolean applied) {
        var selected = actions.stream().filter(action -> action.id().equals(step.action())
                && action.actor().equals(step.actor())).findFirst().orElseThrow(
                        () -> new IllegalStateException("Selected action is no longer retained"));
        if (selected.applied() == applied) return this;
        var stacks = players.get(step.actor());
        var source = new ArrayList<>(applied ? stacks.redo() : stacks.undo());
        var target = new ArrayList<>(applied ? stacks.undo() : stacks.redo());
        var group = actions.stream().filter(action -> action.group().equals(selected.group())).toList();
        var ids = group.stream().map(WorldActionRecord.Metadata::id).toList();
        if (!selected.closed() || source.isEmpty() || !source.getLast().equals(step.action())
                || source.size() < ids.size() || !source.subList(source.size() - ids.size(), source.size()).equals(ids)) {
            throw new IllegalStateException("Selected action cursor changed before publication");
        }
        source.subList(source.size() - ids.size(), source.size()).clear();
        target.addAll(ids);
        var replacement = actions.stream().map(action -> action.group().equals(selected.group())
                ? new WorldActionRecord.Metadata(action.id(), action.actor(), action.sequence(), action.group(),
                        action.revision(), applied, action.closed(), action.pending(), action.checkpoint()) : action).toList();
        var cursors = new HashMap<>(players);
        cursors.put(step.actor(), applied ? new Stacks(target, source) : new Stacks(source, target));
        return new WorldActionIndex(Math.addExact(revision, 1), nextSequence, unsafeBeforeSequence, replacement, cursors);
    }
}
