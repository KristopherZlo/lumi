package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.WorldActionCheckpoint;
import io.github.lumi.domain.model.WorldActionRecord;
import io.github.lumi.domain.model.WorldActionRecord.BlockDelta;
import io.github.lumi.domain.model.WorldActionRecord.EntityDelta;
import io.github.lumi.domain.model.WorldActionIndex;
import io.github.lumi.domain.model.BiomePosition;
import io.github.lumi.domain.model.WorldActionRecord.BiomeDelta;
import io.github.lumi.domain.model.WorldActionRecord.SectionDelta;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldActionScope;
import io.github.lumi.domain.model.WorldActionView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.io.IOException;

/** Ordered action history and ownership indexes for one shared dimension. */
public final class LiveActionJournal {
    /** Expected admission refusal; no world mutation or cursor transition has begun. */
    public static final class OverlapConflict extends IllegalStateException {
        public OverlapConflict() { super("A newer action overlaps the selected live action"); }
    }
    private final Map<UUID, MutableAction> actions = new HashMap<>();
    private final Map<UUID, UUID> openActions = new HashMap<>();
    private final Set<UUID> pendingChanges = new HashSet<>();
    private final Map<UUID, PlayerStacks> playerStacks = new HashMap<>();
    private final OwnershipIndex<BlockPosition> blockOwners = new OwnershipIndex<>();
    private final OwnershipIndex<UUID> entityOwners = new OwnershipIndex<>();
    private final OwnershipIndex<BiomePosition> biomeOwners = new OwnershipIndex<>();
    private final Map<UUID, Long> playerBytes = new HashMap<>();
    private final Map<UUID, String> unavailableReasons = new HashMap<>();
    private final Map<BlockSnapshot, Integer> blockStateReferences = new HashMap<>();
    private final Limits limits;
    private final Consumer<WorldActionCheckpoint> checkpointRelease;
    private final Consumer<BlockSnapshot> blockStateRelease;
    private final LiveEntityStateReferences entityStates;
    private long revision;
    private long nextSequence;
    private long retainedBytes;
    private long unsafeBeforeSequence;

    public LiveActionJournal() {
        this(Limits.DEFAULT, ignored -> { });
    }

    public LiveActionJournal(Limits limits) {
        this(limits, ignored -> { });
    }

    public LiveActionJournal(Consumer<WorldActionCheckpoint> checkpointRelease) {
        this(Limits.DEFAULT, checkpointRelease);
    }

    public LiveActionJournal(
            Limits limits,
            Consumer<WorldActionCheckpoint> checkpointRelease) {
        this(limits, checkpointRelease, ignored -> { });
    }

    /** Release callbacks must be idempotent and must not throw or reenter the journal. */
    public LiveActionJournal(
            Limits limits,
            Consumer<WorldActionCheckpoint> checkpointRelease,
            Consumer<BlockSnapshot> blockStateRelease) {
        this(limits, checkpointRelease, blockStateRelease, new LiveEntityStateReferences(ignored -> { }));
    }

    public LiveActionJournal(
            Limits limits, Consumer<WorldActionCheckpoint> checkpointRelease,
            Consumer<BlockSnapshot> blockStateRelease, LiveEntityStateReferences entityStates) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.checkpointRelease = Objects.requireNonNull(
                checkpointRelease, "checkpointRelease");
        this.blockStateRelease = Objects.requireNonNull(blockStateRelease, "blockStateRelease");
        this.entityStates = Objects.requireNonNull(entityStates, "entityStates");
    }

    public synchronized UUID begin(UUID player) {
        revision++;
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

    /** Closes an unfinished root while preserving any recorded changes for Undo. */
    public synchronized Optional<ActionSummary> recoverOpen(UUID player) {
        UUID actionId = openActions.get(Objects.requireNonNull(player, "player"));
        if (actionId == null) {
            return Optional.empty();
        }
        ActionSummary summary = summary(actionId);
        close(actionId);
        return Optional.of(summary);
    }

    public synchronized UUID record(
            UUID actionId,
            BlockPosition position,
            BlockSnapshot before,
            BlockSnapshot after) {
        revision++;
        MutableAction causalAction = requireAction(actionId);
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (!causalAction.available) {
            discardUnowned(before);
            discardUnowned(after);
            return causalAction.id;
        }
        MutableAction action = effectiveBlockAction(causalAction, position);
        var sectionKey = new SectionKey(position.x() >> 4, position.y() >> 4, position.z() >> 4);
        var section = action.sections.get(sectionKey);
        if (section != null) {
            int cell = ((position.y() & 15) << 8) | ((position.z() & 15) << 4) | (position.x() & 15);
            var combined = section.followedByBlock(cell, before, after);
            recordSection(action.id, sectionKey, section.after(), combined.after());
            discardUnowned(before); discardUnowned(after);
            return action.id;
        }
        BlockDelta previous = action.changes.get(position);
        BlockDelta updated = new BlockDelta(previous == null ? before : previous.before(), after);
        long previousBytes = previous == null ? 0 : estimatedBytes(previous);
        long updatedBytes = updated.before().equals(updated.after()) ? 0 : estimatedBytes(updated);
        // Pin the new endpoints before clearing Redo or evicting another owner.
        if (updatedBytes != 0) retainBlockStates(updated);
        boolean recorded = false;
        try {
            if (previous == null && updatedBytes == 0) return action.id;
            startRecording(action);
            if (!resize(action, updatedBytes - previousBytes)) return action.id;
            if (previous != null) releaseBlockStates(previous);
            if (updatedBytes == 0) action.changes.remove(position);
            else action.changes.put(position, updated);
            recorded = true;
            action.revision++;
            blockOwners.set(position, action, updatedBytes != 0 && action.applied);
            return action.id;
        } finally {
            if (!recorded && updatedBytes != 0) releaseBlockStates(updated);
            discardUnowned(before);
            discardUnowned(after);
        }
    }

    public synchronized UUID recordEntity(
            UUID actionId,
            UUID entityId,
            Optional<EntityState> before,
            Optional<EntityState> after) {
        revision++;
        MutableAction causalAction = requireAction(actionId);
        if (!causalAction.available) {
            entityStates.discardUnowned(before);
            entityStates.discardUnowned(after);
            return causalAction.id;
        }
        Objects.requireNonNull(entityId, "entityId");
        validateEntityId(entityId, before);
        validateEntityId(entityId, after);
        MutableAction action = effectiveAction(
                causalAction, entityId, entityOwners);
        EntityDelta previous = action.entities.get(entityId);
        EntityDelta updated = new EntityDelta(
                previous == null ? before : previous.before(), after);
        try {
            return replaceEntity(action, entityId, updated);
        } finally {
            entityStates.discardUnowned(before);
            entityStates.discardUnowned(after);
        }
    }

    public synchronized void recordBiome(UUID actionId, BiomePosition position, String before, String after) {
        revision++;
        var action = effectiveAction(requireAction(actionId), position, biomeOwners);
        if (!action.available) return;
        var previous = action.biomes.get(position);
        var delta = new BiomeDelta(previous == null ? before : previous.before(), after);
        long oldBytes = previous == null ? 0 : biomeBytes(previous);
        long bytes = delta.before().equals(delta.after()) ? 0 : biomeBytes(delta);
        if (previous == null && bytes == 0) return;
        startRecording(action);
        if (!resize(action, bytes - oldBytes)) return;
        if (bytes == 0) action.biomes.remove(position); else action.biomes.put(position, delta);
        action.revision++;
        biomeOwners.set(position, action, bytes != 0 && action.applied);
    }

    private static long biomeBytes(BiomeDelta delta) { return 256L + 2L * (delta.before().length() + delta.after().length()); }

    public synchronized void recordSection(UUID actionId, SectionKey key, SectionBlob before, SectionBlob after) {
        revision++;
        var action = requireAction(actionId);
        if (!action.available || before.equals(after)) return;
        var previous = action.sections.get(key);
        var delta = previous == null ? new SectionDelta(before, after) : previous.followedBy(new SectionDelta(before, after));
        startRecording(action);
        long oldBytes = previous == null ? 0 : sectionBytes(previous);
        long bytes = delta.before().equals(delta.after()) ? 0 : sectionBytes(delta);
        if (!resize(action, bytes - oldBytes)) return;
        var covered = action.changes.entrySet().iterator();
        while (covered.hasNext()) {
            var entry = covered.next();
            if ((entry.getKey().x() >> 4) != key.chunkX() || (entry.getKey().y() >> 4) != key.sectionY()
                    || (entry.getKey().z() >> 4) != key.chunkZ()) continue;
            adjustBytes(action.player, -estimatedBytes(entry.getValue()));
            action.bytes -= estimatedBytes(entry.getValue());
            blockOwners.set(entry.getKey(), action, false);
            releaseBlockStates(entry.getValue());
            covered.remove();
        }
        if (bytes == 0) { action.sections.remove(key); action.sectionScopes.remove(key); }
        else {
            action.sections.put(key, delta);
            action.sectionScopes.put(key, sectionScope(key, delta));
        }
        action.revision++;
    }

    private static WorldActionScope sectionScope(SectionKey key, SectionDelta delta) {
        return WorldActionScope.between(Map.of(key, delta.before()), Map.of(key, delta.after()), Map.of(), Map.of());
    }

    private static long sectionBytes(SectionDelta delta) { return 1024 + delta.before().retainedActionBytes() + delta.after().retainedActionBytes(); }

    private UUID replaceEntity(MutableAction action, UUID entityId, EntityDelta updated) {
        EntityDelta previous = action.entities.get(entityId);
        long previousBytes = previous == null ? 0 : estimatedBytes(previous);
        long updatedBytes = updated.before().equals(updated.after()) ? 0 : estimatedBytes(updated);
        if (updatedBytes != 0) {
            entityStates.retain(updated.before());
            entityStates.retain(updated.after());
        }
        boolean recorded = false;
        try {
            if (previous == null && updatedBytes == 0) return action.id;
            startRecording(action);
            if (!resize(action, updatedBytes - previousBytes)) return action.id;
            if (previous != null) releaseEntityStates(previous);
            if (updatedBytes == 0) action.entities.remove(entityId);
            else action.entities.put(entityId, updated);
            recorded = true;
            action.revision++;
            entityOwners.set(entityId, action, updatedBytes != 0 && action.applied);
            return action.id;
        } finally {
            if (!recorded && updatedBytes != 0) releaseEntityStates(updated);
            entityStates.discardUnowned(updated.before());
            entityStates.discardUnowned(updated.after());
        }
    }

    /** Adds durable Restore endpoints and their compact conflict scope to the stack. */
    public synchronized UUID pushCheckpoint(UUID player, WorldActionCheckpoint checkpoint) {
        revision++;
        Objects.requireNonNull(checkpoint, "checkpoint");
        UUID actionId = begin(player);
        try {
            MutableAction action = requireAction(actionId);
            startRecording(action);
            action.checkpoint = checkpoint;
            action.revision++;
            resize(action, checkpoint.scope().estimatedBytes());
            return actionId;
        } finally {
            close(actionId);
        }
    }

    /** Joins causally inseparable actions into one session Undo/Redo entry. */
    public synchronized void mergeGroups(UUID firstAction, UUID secondAction) {
        revision++;
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
            stacks(first.player).mergeGroups(first.groupId, source);
            actions.values().stream()
                    .filter(action -> action.groupId.equals(source))
                    .forEach(action -> action.groupId = first.groupId);
        }
        coalesceClosedGroup(first);
    }

    public synchronized void refreshEntityBefore(
            UUID actionId,
            UUID entityId,
            Optional<EntityState> before) {
        revision++;
        MutableAction selected = requireAction(actionId);
        MutableAction action = actions.values().stream()
                .filter(candidate -> candidate.groupId.equals(selected.groupId)
                        && candidate.entities.containsKey(entityId))
                .min(java.util.Comparator.comparingLong(candidate -> candidate.sequence))
                .orElseThrow(() -> new IllegalStateException(
                        "Entity is not part of the live action group: " + entityId));
        if (!action.available) {
            entityStates.discardUnowned(before);
            return;
        }
        if (action.applied) {
            throw new IllegalStateException("Cannot refresh before-state of an applied action");
        }
        Objects.requireNonNull(entityId, "entityId");
        validateEntityId(entityId, before);
        EntityDelta previous = action.entities.get(entityId);
        replaceEntity(action, entityId, new EntityDelta(before, previous.after()));
    }

    public synchronized boolean close(UUID actionId) {
        revision++;
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
            evictOne(action);
            return false;
        }
        PlayerStacks stacks = stacks(action.player);
        stacks.undo.addLast(action.id);
        stacks.add(action.groupId);
        if (stacks.groupMembers.get(action.groupId) > 1) coalesceClosedGroup(action);
        trimToActionLimit(action.player, action.groupId);
        return true;
    }

    public synchronized void retain(UUID actionId) {
        revision++;
        requireAction(actionId).causalReferences++;
    }

    /** A streamed edit is not undoable until its producer finishes or drains. */
    public synchronized void holdForMoreChanges(UUID actionId) {
        revision++;
        requireAction(actionId);
        if (pendingChanges.add(actionId)) retain(actionId);
    }

    public synchronized void finishChanges(UUID actionId) {
        revision++;
        if (pendingChanges.remove(actionId)) release(actionId);
    }

    public synchronized boolean isAvailable(UUID actionId) {
        MutableAction action = actions.get(Objects.requireNonNull(actionId, "actionId"));
        return action != null && action.available;
    }

    public synchronized void release(UUID actionId) {
        revision++;
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
            evictOne(action);
        }
        trimToActionLimit(action.player, null);
    }

    public synchronized Optional<Plan> prepareUndo(UUID player) {
        return prepare(player, Direction.UNDO, stacks(player).undo);
    }

    public synchronized Optional<Plan> prepareRedo(UUID player) {
        return prepare(player, Direction.REDO, stacks(player).redo);
    }

    /** One native entry may own several explicitly grouped dispatches. */
    public synchronized boolean canAppendAxiom(UUID actor, UUID entry) {
        return actions.values().stream().noneMatch(action -> action.player.equals(actor)
                && entry.equals(action.axiomEntry) && !action.applied);
    }

    public synchronized void linkAxiom(UUID actionId, UUID entry) {
        MutableAction action = requireAction(actionId);
        Objects.requireNonNull(entry, "entry");
        if (action.axiomEntry != null && !entry.equals(action.axiomEntry))
            throw new IllegalStateException("Action already belongs to another native entry");
        if (action.axiomEntry == null) {
            var previous = actions.values().stream().filter(other -> other.available && other.player.equals(action.player)
                    && entry.equals(other.axiomEntry)).findFirst();
            previous.ifPresent(other -> mergeGroups(other.id, action.id));
            action.axiomEntry = entry; revision++;
        }
    }

    /** Native controls select their explicit entry, never an inferred last edit. */
    public synchronized Optional<Plan> prepareAxiom(UUID player, UUID entry, Direction direction) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(direction, "direction");
        var retained = playerStacks.get(Objects.requireNonNull(player, "player"));
        if (retained == null) return Optional.empty();
        var cursor = direction == Direction.UNDO ? retained.undo : retained.redo;
        return cursor.stream().filter(id -> entry.equals(requireAction(id).axiomEntry))
                .reduce((older, newer) -> newer).flatMap(id -> prepareSelected(player, id, direction));
    }

    /** Native buffers also write cells that happened to be unchanged in the original edit. */
    public synchronized void requireNativeFootprint(Plan plan, WorldActionScope blocks, Set<BiomePosition> biomes) {
        long sequence = plan.actionIds().stream().map(this::requireAction).mapToLong(action -> action.sequence).min().orElseThrow();
        UUID group = requireAction(plan.actionId()).groupId;
        for (var action : actions.values()) {
            if (!action.available || !action.applied || action.sequence <= sequence || action.groupId.equals(group)) continue;
            if (blocks.intersects(action.changes.keySet(), action.entities.keySet())
                    || action.sectionScopes.values().stream().anyMatch(blocks::intersects)
                    || action.checkpoint != null && blocks.intersects(action.checkpoint.scope())
                    || biomes.stream().anyMatch(action.biomes::containsKey)) throw new OverlapConflict();
        }
    }

    /** Selects one retained group; ordinary overlap and eviction checks still apply. */
    public synchronized Optional<Plan> prepareSelected(UUID player, UUID actionId, Direction direction) {
        Objects.requireNonNull(direction, "direction");
        MutableAction selected = requireAction(actionId);
        if (!selected.player.equals(player)) throw new IllegalArgumentException("Live action belongs to another builder");
        Deque<UUID> source = direction == Direction.UNDO ? stacks(player).undo : stacks(player).redo;
        var group = new ArrayDeque<UUID>();
        source.stream().filter(id -> requireAction(id).groupId.equals(selected.groupId)).forEach(group::addLast);
        return prepare(player, direction, group);
    }

    public synchronized void completeSelected(Plan plan) {
        if (!prepareSelected(plan.player(), plan.actionId(), plan.direction()).equals(Optional.of(plan))) {
            throw new IllegalStateException("Selected live action changed during apply");
        }
        installCursor(snapshotIndex().moveSelected(
                new io.github.lumi.domain.model.ActionReplayTarget(plan.player(), plan.actionId(), plan.direction() == Direction.UNDO),
                plan.direction() == Direction.REDO));
    }

    public synchronized void complete(Plan plan) {
        revision++;
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
            setApplied(action, plan.direction == Direction.REDO);
        }
        for (int index = 0; index < plan.actionIds.size(); index++) {
            source.removeLast();
        }
        plan.actionIds.forEach(target::addLast);
    }

    /** Installs a recovered durable cursor without executing the world operation again. */
    public synchronized void installCursor(io.github.lumi.domain.model.ActionReplayTarget step, boolean applied) {
        installCursor(snapshotIndex().move(step, applied));
    }

    private void installCursor(WorldActionIndex index) {
        index.actions().forEach(metadata -> setApplied(requireAction(metadata.id()), metadata.applied()));
        index.players().forEach((actor, cursor) -> {
            var stacks = stacks(actor);
            stacks.undo.clear(); stacks.undo.addAll(cursor.undo());
            stacks.redo.clear(); stacks.redo.addAll(cursor.redo());
        });
        revision = Math.max(revision + 1, index.revision());
    }

    private void setApplied(MutableAction action, boolean applied) {
        action.applied = applied;
        action.changes.keySet().forEach(position -> blockOwners.set(position, action, applied));
        action.entities.keySet().forEach(entity -> entityOwners.set(entity, action, applied));
        action.biomes.keySet().forEach(position -> biomeOwners.set(position, action, applied));
    }

    public synchronized void clear() {
        revision++;
        List<WorldActionCheckpoint> checkpoints = actions.values().stream()
                .map(action -> action.checkpoint)
                .filter(Objects::nonNull)
                .toList();
        actions.values().forEach(action -> action.entities.values().forEach(this::releaseEntityStates));
        actions.clear();
        openActions.clear();
        pendingChanges.clear();
        playerStacks.clear();
        blockOwners.clear();
        entityOwners.clear();
        biomeOwners.clear();
        playerBytes.clear();
        unavailableReasons.clear();
        retainedBytes = 0;
        unsafeBeforeSequence = 0;
        blockStateReferences.keySet().forEach(blockStateRelease);
        blockStateReferences.clear();
        checkpoints.forEach(checkpointRelease);
    }

    public synchronized Optional<String> lastUnavailableReason(UUID player) {
        return Optional.ofNullable(unavailableReasons.get(Objects.requireNonNull(player, "player")));
    }

    public synchronized long revision() { return revision; }

    public synchronized WorldActionView view(UUID player) {
        var cursor = playerStacks.get(Objects.requireNonNull(player, "player"));
        if (cursor == null) return new WorldActionView(revision, List.of(), List.of());
        return new WorldActionView(revision, view(cursor.undo, false), view(cursor.redo, true));
    }

    private List<WorldActionView.Entry> view(Deque<UUID> cursor, boolean reverse) {
        var groups = new LinkedHashMap<UUID, MutableAction>();
        var links = new HashMap<UUID, Set<UUID>>();
        cursor.forEach(id -> {
            var action = requireAction(id);
            groups.put(action.groupId, action);
            if (action.axiomEntry != null) links.computeIfAbsent(action.groupId, ignored -> new LinkedHashSet<>()).add(action.axiomEntry);
        });
        var values = new java.util.ArrayList<>(groups.values());
        if (reverse) Collections.reverse(values);
        return values.stream().map(action -> {
            var kind = WorldActionView.Kind.EDIT;
            if (action.checkpoint != null) {
                kind = WorldActionView.Kind.RESTORE;
                if (action.checkpoint.context().isPresent()) {
                    var context = action.checkpoint.context().orElseThrow();
                    kind = context.before().workspace().equals(context.after().workspace())
                            ? WorldActionView.Kind.BRANCH_SWITCH : WorldActionView.Kind.WORKSPACE_SWITCH;
                }
            }
            Optional<BlockPosition> origin = action.changes.keySet().stream().findFirst();
            if (origin.isEmpty()) {
                var keys = action.checkpoint == null ? action.sections.keySet() : action.checkpoint.scope().sections();
                origin = keys.stream().findFirst().map(key -> new BlockPosition(key.chunkX() << 4, key.sectionY() << 4, key.chunkZ() << 4));
            }
            return new WorldActionView.Entry(action.id, kind, origin, List.copyOf(links.getOrDefault(action.groupId, Set.of())));
        }).toList();
    }

    /** Captures only order/ownership metadata; payloads are copied individually off-thread. */
    public synchronized WorldActionIndex snapshotIndex() {
        var records = actions.values().stream().filter(action -> action.available)
                .sorted(java.util.Comparator.comparingLong(action -> action.sequence))
                .map(action -> new WorldActionRecord.Metadata(
                        action.id, action.player, action.sequence, action.groupId, action.revision,
                        action.applied, action.closed, !action.closed || action.delayedReferences > 0
                                || pendingChanges.contains(action.id), Optional.ofNullable(action.checkpoint), Optional.ofNullable(action.axiomEntry)))
                .toList();
        var players = new HashMap<UUID, WorldActionIndex.Stacks>();
        playerStacks.forEach((player, stacks) -> players.put(player,
                new WorldActionIndex.Stacks(List.copyOf(stacks.undo), List.copyOf(stacks.redo))));
        return new WorldActionIndex(revision, nextSequence, unsafeBeforeSequence, records, players);
    }

    /** A changed action retries next pass rather than publishing mismatched payload and cursor. */
    public synchronized WorldActionRecord snapshotAction(WorldActionRecord.Metadata expected) {
        MutableAction action = actions.get(expected.id());
        if (action == null || !action.available || action.revision != expected.revision()
                || !action.player.equals(expected.actor()) || action.sequence != expected.sequence()) {
            throw new java.util.ConcurrentModificationException("Action changed during history capture");
        }
        return new WorldActionRecord(expected, new WorldActionRecord.Payload(action.changes, action.entities, action.biomes, action.sections));
    }

    public synchronized Map<UUID, EntityDelta> entityChanges(UUID actionId) {
        return Map.copyOf(requireAction(actionId).entities);
    }

    /** Startup only, off-thread. An interrupted producer is retained on disk but cannot be replayed. */
    public synchronized void restore(WorldActionIndex index, ActionSource source) throws IOException {
        if (!actions.isEmpty() || nextSequence != 0) throw new IllegalStateException("History was already opened");
        for (var metadata : index.actions()) restoreRecord(metadata, source.read(metadata));
        installStacks(index);
        nextSequence = index.nextSequence();
        unsafeBeforeSequence = index.unsafeBeforeSequence();
        revision = index.revision();
        for (var metadata : index.actions()) {
            if (metadata.pending()) {
                var action = actions.get(metadata.id());
                if (action != null && action.available) {
                    action.closed = true;
                    makeUnavailable(action, "The edit was still arriving when the world closed");
                }
            }
        }
        revision++;
    }

    /** Worker phase under the dimension freeze; unchanged live owners retain their causal leases. */
    public synchronized void installIndex(WorldActionIndex index, ActionSource source) throws IOException {
        var retained = index.actions().stream().map(WorldActionRecord.Metadata::id).collect(java.util.stream.Collectors.toSet());
        for (var action : List.copyOf(actions.values())) {
            if (action.available && !retained.contains(action.id)) evictOne(action);
        }
        for (var metadata : index.actions()) {
            var existing = actions.get(metadata.id());
            if (existing == null || !existing.available) restoreRecord(metadata, source.read(metadata));
            else if (existing.revision != metadata.revision()) throw new IOException("Action changed while installing a prepared history root");
            var action = requireAction(metadata.id());
            action.groupId = metadata.group();
            action.axiomEntry = metadata.axiomEntry().orElse(null);
            action.closed = metadata.closed();
            setApplied(action, metadata.applied());
        }
        playerStacks.clear();
        installStacks(index);
        nextSequence = Math.max(nextSequence, index.nextSequence());
        unsafeBeforeSequence = index.unsafeBeforeSequence();
        revision = Math.max(revision + 1, index.revision());
    }

    private void installStacks(WorldActionIndex index) {
        index.players().forEach((actor, cursor) -> {
            var stacks = stacks(actor);
            stacks.undo.addAll(cursor.undo());
            stacks.redo.addAll(cursor.redo());
            cursor.undo().forEach(id -> stacks.add(requireAction(id).groupId));
            cursor.redo().forEach(id -> stacks.add(requireAction(id).groupId));
        });
    }

    private void restoreRecord(WorldActionRecord.Metadata metadata, WorldActionRecord record) throws IOException {
        if (!record.metadata().equals(metadata)) throw new IOException("Restored action identity mismatch");
        var action = new MutableAction(metadata.id(), metadata.actor(), metadata.sequence());
        action.groupId = metadata.group();
        action.axiomEntry = metadata.axiomEntry().orElse(null);
        action.revision = metadata.revision();
        action.applied = metadata.applied();
        action.closed = metadata.closed();
        action.started = true;
        action.checkpoint = metadata.checkpoint().orElse(null);
        action.changes.putAll(record.payload().blocks());
        action.entities.putAll(record.payload().entities());
        action.biomes.putAll(record.payload().biomes());
        action.sections.putAll(record.payload().sections());
        action.sections.forEach((key, delta) -> action.sectionScopes.put(key, sectionScope(key, delta)));
        action.bytes = action.changes.values().stream().mapToLong(LiveActionJournal::estimatedBytes).sum()
                + action.entities.values().stream().mapToLong(LiveActionJournal::estimatedBytes).sum()
                + action.biomes.values().stream().mapToLong(LiveActionJournal::biomeBytes).sum()
                + action.sections.values().stream().mapToLong(LiveActionJournal::sectionBytes).sum()
                + metadata.checkpoint().map(value -> value.scope().estimatedBytes()).orElse(0L);
        if (action.bytes > limits.maxActionBytes || retainedBytes + action.bytes > limits.maxDimensionBytes
                || playerBytes.getOrDefault(action.player, 0L) + action.bytes > limits.maxPlayerBytes) {
            throw new IOException("Stored history exceeds its memory budget");
        }
        actions.put(action.id, action);
        adjustBytes(action.player, action.bytes);
        action.changes.forEach((position, delta) -> {
            retainBlockStates(delta);
            blockOwners.set(position, action, action.applied);
        });
        action.entities.forEach((id, delta) -> {
            entityStates.retain(delta.before());
            entityStates.retain(delta.after());
            entityOwners.set(id, action, action.applied);
        });
        action.biomes.keySet().forEach(position -> biomeOwners.set(position, action, action.applied));
    }

    @FunctionalInterface
    public interface ActionSource { WorldActionRecord read(WorldActionRecord.Metadata metadata) throws IOException; }

    public synchronized void retainDelayed(UUID actionId) {
        retain(actionId);
        requireAction(actionId).delayedReferences++;
    }

    public synchronized void releaseDelayed(UUID actionId) {
        var action = actions.get(actionId);
        if (action == null || action.delayedReferences == 0) throw new IllegalStateException("Unbalanced delayed action lease");
        action.delayedReferences--;
        release(actionId);
    }

    public synchronized boolean hasEntityChange(UUID actionId, UUID entityId) {
        MutableAction action = actions.get(Objects.requireNonNull(actionId, "actionId"));
        return action != null && action.available && action.entities.containsKey(entityId);
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
        revision++;
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
        List<MutableAction> group = stack.stream()
                .map(this::requireAction)
                .filter(action -> action.groupId.equals(selected.groupId))
                .toList();
        if (actions.values().stream().anyMatch(action -> action.available
                && action.groupId.equals(selected.groupId)
                && (!action.closed || pendingChanges.contains(action.id)))) {
            throw new IllegalStateException("The selected builder edit is still arriving");
        }
        if (group.stream().anyMatch(action -> action.sequence <= unsafeBeforeSequence)) {
            throw new IllegalStateException("A newer evicted action makes this live action unsafe");
        }
        requireNoCheckpointOverlap(group);
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
        Set<UUID> groupIds = group.stream()
                .map(action -> action.id)
                .collect(java.util.stream.Collectors.toSet());
        for (MutableAction action : group) {
            for (var position : action.biomes.keySet()) {
                if (overlaps(action, direction, biomeOwners.latest(position), groupIds)) {
                    throw new OverlapConflict();
                }
            }
            for (BlockPosition position : action.changes.keySet()) {
                if (overlaps(
                        action, direction, blockOwners.latest(position), groupIds)) {
                    throw new OverlapConflict();
                }
            }
            for (UUID entity : action.entities.keySet()) {
                if (overlaps(
                        action, direction, entityOwners.latest(entity), groupIds)) {
                    throw new OverlapConflict();
                }
            }
        }
        Map<BlockPosition, BlockDelta> blocks = new LinkedHashMap<>();
        Map<UUID, EntityDelta> entities = new LinkedHashMap<>();
        Map<BiomePosition, BiomeDelta> biomes = new LinkedHashMap<>();
        Map<SectionKey, SectionDelta> sectionChanges = new LinkedHashMap<>();
        var sectionSeeds = new HashMap<SectionKey, SectionBlob>();
        group.forEach(action -> action.sections.forEach((key, delta) -> sectionSeeds.putIfAbsent(key, delta.before())));
        for (MutableAction action : group) {
            action.sections.forEach((key, delta) -> sectionChanges.merge(key, delta, SectionDelta::followedBy));
            action.biomes.forEach((position, change) -> biomes.merge(position, change,
                    (previous, latest) -> new BiomeDelta(previous.before(), latest.after())));
            action.changes.forEach((position, change) -> {
                var key = new SectionKey(position.x() >> 4, position.y() >> 4, position.z() >> 4);
                if (sectionSeeds.containsKey(key)) {
                    var seed = sectionSeeds.get(key);
                    var delta = sectionChanges.getOrDefault(key, new SectionDelta(seed, seed));
                    int cell = ((position.y() & 15) << 8) | ((position.z() & 15) << 4) | (position.x() & 15);
                    sectionChanges.put(key, delta.followedByBlock(cell, change.before(), change.after()));
                } else blocks.merge(position, change, (previous, latest) -> new BlockDelta(previous.before(), latest.after()));
            });
            action.entities.forEach((entity, change) -> entities.merge(
                    entity, change,
                    (previous, latest) ->
                            new EntityDelta(previous.before(), latest.after())));
        }
        List<MutableAction> applicationOrder = direction == Direction.UNDO
                ? group.reversed() : group;
        Set<BlockPosition> blockOrder = new LinkedHashSet<>();
        Set<UUID> entityOrder = new LinkedHashSet<>();
        for (MutableAction action : applicationOrder) {
            action.changes.keySet().stream().filter(blocks::containsKey).forEach(blockOrder::add);
            entityOrder.addAll(action.entities.keySet());
        }
        Map<BlockPosition, BlockSnapshot> expected = new LinkedHashMap<>();
        Map<BlockPosition, BlockSnapshot> replacement = new LinkedHashMap<>();
        Map<UUID, Optional<EntityState>> expectedEntities = new LinkedHashMap<>();
        Map<UUID, Optional<EntityState>> replacementEntities = new LinkedHashMap<>();
        blockOrder.forEach(position -> {
            BlockDelta change = blocks.get(position);
            expected.put(position, direction == Direction.UNDO ? change.after() : change.before());
            replacement.put(position, direction == Direction.UNDO ? change.before() : change.after());
        });
        entityOrder.forEach(id -> {
            EntityDelta change = entities.get(id);
            expectedEntities.put(id, direction == Direction.UNDO ? change.after() : change.before());
            replacementEntities.put(id, direction == Direction.UNDO ? change.before() : change.after());
        });
        return Optional.of(new Plan(
                player, group.stream().map(action -> action.id).toList(),
                direction, expected, replacement,
                expectedEntities, replacementEntities, Optional.empty(), biomes, sectionChanges));
    }

    private MutableAction requireAction(UUID id) {
        MutableAction action = actions.get(Objects.requireNonNull(id, "actionId"));
        if (action == null) {
            throw new IllegalArgumentException("Unknown live action: " + id);
        }
        return action;
    }

    private void requireNoCheckpointOverlap(List<MutableAction> group) {
        for (MutableAction selected : group) {
            for (MutableAction later : actions.values()) {
                if (!later.applied || !later.available || later.sequence <= selected.sequence
                        || later.groupId.equals(selected.groupId)) continue;
                if (later.checkpoint != null && later.checkpoint.context().isPresent()) {
                    throw new IllegalStateException("A newer branch or workspace switch must be undone first");
                }
                if (selected.overlapsCheckpoint(later)) {
                    throw new OverlapConflict();
                }
            }
        }
    }

    private MutableAction effectiveBlockAction(MutableAction causalAction, BlockPosition position) {
        if (!causalAction.closed) return causalAction;
        MutableAction latest = blockOwners.latest(position)
                .map(owner -> requireAction(owner.actionId)).orElse(causalAction);
        var key = new SectionKey(position.x() >> 4, position.y() >> 4, position.z() >> 4);
        int cell = ((position.y() & 15) << 8) | ((position.z() & 15) << 4) | (position.x() & 15);
        // Compact sections have no per-block ownership entries.
        for (var candidate : actions.values()) {
            if (!candidate.available || !candidate.applied || candidate.sequence <= latest.sequence) continue;
            var section = candidate.sections.get(key);
            if (section != null && section.changed(cell)) latest = candidate;
        }
        return latest.sequence > causalAction.sequence && latest.player.equals(causalAction.player)
                ? latest : causalAction;
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

    private void coalesceClosedGroup(MutableAction action) {
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
        // Keep the group at its latest member, below any later independent edits.
        List<UUID> remaining = new java.util.ArrayList<>();
        int insertion = 0;
        for (UUID id : stacks.undo) {
            if (requireAction(id).groupId.equals(action.groupId)) insertion = remaining.size();
            else remaining.add(id);
        }
        remaining.addAll(insertion, members);
        stacks.undo.clear();
        stacks.undo.addAll(remaining);
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
        return action.changes.isEmpty() && action.entities.isEmpty() && action.biomes.isEmpty() && action.sections.isEmpty()
                && action.checkpoint == null;
    }

    private PlayerStacks stacks(UUID player) {
        return playerStacks.computeIfAbsent(Objects.requireNonNull(player, "player"), ignored -> new PlayerStacks());
    }

    private void clearRedo(UUID player) {
        for (UUID id : java.util.List.copyOf(stacks(player).redo)) {
            MutableAction action = actions.get(id);
            if (action != null) {
                evictOne(action);
            }
        }
    }

    private boolean makeRoom(MutableAction current, long addedBytes) {
        while (playerBytes.getOrDefault(current.player, 0L) + addedBytes
                > limits.maxPlayerBytes) {
            Optional<MutableAction> oldest = oldestClosed(
                    current.player, current.groupId);
            if (oldest.isEmpty()) {
                return false;
            }
            evict(oldest.orElseThrow());
        }
        while (retainedBytes + addedBytes > limits.maxDimensionBytes) {
            Optional<MutableAction> oldest = oldestClosed(current.groupId);
            if (oldest.isEmpty()) {
                return false;
            }
            evict(oldest.orElseThrow());
        }
        return true;
    }

    private Optional<MutableAction> oldestClosed(
            UUID player,
            UUID excludedGroup) {
        Set<UUID> activeGroups = activeGroups();
        return actions.values().stream()
                .filter(action -> action.player.equals(player) && action.closed
                        && action.available
                        && !action.groupId.equals(excludedGroup))
                .min(java.util.Comparator.comparingLong(action -> action.sequence))
                .filter(action -> !activeGroups.contains(action.groupId));
    }

    private Optional<MutableAction> oldestClosed(UUID excludedGroup) {
        Set<UUID> activeGroups = activeGroups();
        return actions.values().stream()
                .filter(action -> action.closed && action.available
                        && !action.groupId.equals(excludedGroup))
                .min(java.util.Comparator.comparingLong(action -> action.sequence))
                .filter(action -> !activeGroups.contains(action.groupId));
    }

    private Set<UUID> activeGroups() {
        Set<UUID> groups = new HashSet<>();
        actions.values().stream()
                .filter(action -> !action.closed || action.causalReferences > 0)
                .forEach(action -> groups.add(action.groupId));
        return groups;
    }

    private void trimToActionLimit(UUID player, UUID excludedGroup) {
        while (stackSize(player) > limits.maxActionsPerPlayer) {
            Optional<MutableAction> oldest = oldestClosed(player, excludedGroup);
            if (oldest.isEmpty()) {
                return;
            }
            evict(oldest.orElseThrow());
        }
    }

    private int stackSize(UUID player) {
        return stacks(player).groupCount();
    }

    private void makeUnavailable(MutableAction action, String reason) {
        unavailableReasons.put(action.player, reason);
        evict(action);
    }

    private void evict(MutableAction action) {
        if (!action.available) {
            evictOne(action);
            return;
        }
        List<MutableAction> group = actions.values().stream()
                .filter(candidate -> candidate.available
                        && candidate.groupId.equals(action.groupId))
                .toList();
        long barrier = group.stream()
                .mapToLong(candidate -> candidate.sequence)
                .max().orElse(action.sequence);
        group.forEach(this::evictOne);
        unsafeBeforeSequence = Math.max(unsafeBeforeSequence, barrier);
    }

    private void evictOne(MutableAction action) {
        if (!action.available) {
            if (action.closed && action.causalReferences == 0) {
                actions.remove(action.id);
            }
            return;
        }
        evictState(action);
        action.available = false;
        if (action.closed && action.causalReferences == 0) {
            actions.remove(action.id);
        }
        releaseCheckpoint(action);
    }

    private void evictState(MutableAction action) {
        adjustBytes(action.player, -action.bytes);
        action.bytes = 0;
        action.changes.keySet().forEach(position -> blockOwners.set(position, action, false));
        action.entities.keySet().forEach(entity -> entityOwners.set(entity, action, false));
        action.biomes.keySet().forEach(position -> biomeOwners.set(position, action, false));
        action.changes.values().forEach(this::releaseBlockStates);
        action.entities.values().forEach(this::releaseEntityStates);
        action.changes.clear();
        action.entities.clear();
        action.biomes.clear();
        action.sections.clear();
        action.sectionScopes.clear();
        PlayerStacks stacks = stacks(action.player);
        if (stacks.undo.remove(action.id) || stacks.redo.remove(action.id)) {
            stacks.remove(action.groupId);
        }
    }

    private void releaseEntityStates(EntityDelta change) {
        entityStates.release(change.before());
        entityStates.release(change.after());
    }

    private void retainBlockStates(BlockDelta change) {
        blockStateReferences.merge(change.before(), 1, Integer::sum);
        blockStateReferences.merge(change.after(), 1, Integer::sum);
    }

    private void releaseBlockStates(BlockDelta change) {
        releaseBlockState(change.before());
        releaseBlockState(change.after());
    }

    private void releaseBlockState(BlockSnapshot snapshot) {
        int remaining = blockStateReferences.get(snapshot) - 1;
        if (remaining == 0) {
            blockStateReferences.remove(snapshot);
            blockStateRelease.accept(snapshot);
        } else {
            blockStateReferences.put(snapshot, remaining);
        }
    }

    private void discardUnowned(BlockSnapshot snapshot) {
        if (!blockStateReferences.containsKey(snapshot)) blockStateRelease.accept(snapshot);
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

    private static long estimatedBytes(BlockDelta change) {
        return 384L + estimatedBytes(change.before()) + estimatedBytes(change.after());
    }

    private static long estimatedBytes(BlockSnapshot snapshot) {
        return snapshot.blockState().getBytes(StandardCharsets.UTF_8).length
                + snapshot.blockEntity().map(nbt -> nbt.byteSize()).orElse(0);
    }

    private static long estimatedBytes(EntityDelta change) {
        return 512L + change.before().map(LiveActionJournal::estimatedBytes).orElse(0L)
                + change.after().map(LiveActionJournal::estimatedBytes).orElse(0L);
    }

    private static long estimatedBytes(EntityState state) {
        return 24L + state.type().getBytes(StandardCharsets.UTF_8).length + state.nbt().byteSize();
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
            Optional<WorldActionCheckpoint> checkpoint, Map<BiomePosition, BiomeDelta> biomes, Map<SectionKey, SectionDelta> sections) {
        public Plan(UUID player, List<UUID> actionIds, Direction direction,
                Map<BlockPosition, BlockSnapshot> expected, Map<BlockPosition, BlockSnapshot> replacement,
                Map<UUID, Optional<EntityState>> expectedEntities, Map<UUID, Optional<EntityState>> replacementEntities,
                Optional<WorldActionCheckpoint> checkpoint) {
            this(player, actionIds, direction, expected, replacement, expectedEntities, replacementEntities, checkpoint, Map.of(), Map.of());
        }
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
            biomes = immutableOrderedCopy(biomes);
            sections = immutableOrderedCopy(sections);
        }

        public UUID actionId() {
            return actionIds.getLast();
        }

        public Map<SectionKey, SectionBlob> expectedSections() { return sectionEndpoint(direction == Direction.UNDO); }
        public Map<SectionKey, SectionBlob> replacementSections() { return sectionEndpoint(direction != Direction.UNDO); }

        private Map<SectionKey, SectionBlob> sectionEndpoint(boolean after) {
            var endpoint = new LinkedHashMap<SectionKey, SectionBlob>();
            sections.forEach((key, delta) -> endpoint.put(key, after ? delta.after() : delta.before()));
            return Collections.unmodifiableMap(endpoint);
        }

        private static <K, V> Map<K, V> immutableOrderedCopy(Map<K, V> source) {
            var copy = new LinkedHashMap<K, V>();
            Objects.requireNonNull(source, "source").forEach((key, value) -> copy.put(
                    Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, "value")));
            return Collections.unmodifiableMap(copy);
        }
    }

    private void releaseCheckpoint(MutableAction action) {
        WorldActionCheckpoint checkpoint = action.checkpoint;
        action.checkpoint = null;
        if (checkpoint != null) {
            checkpointRelease.accept(checkpoint);
        }
    }

    public record ActionSummary(
            UUID actionId,
            UUID player,
            int blocks,
            int entities,
            long bytes,
            int delayedReferences) { }

    private static final class MutableAction {
        private boolean overlapsCheckpoint(MutableAction other) {
            for (var entry : sectionScopes.entrySet()) {
                var scope = entry.getValue();
                if (scope.intersects(other.changes.keySet(), Set.of())
                        || other.checkpoint != null && scope.intersects(other.checkpoint.scope())
                        || other.sectionScopes.containsKey(entry.getKey()) && scope.intersects(other.sectionScopes.get(entry.getKey()))) return true;
            }
            if (other.sectionScopes.values().stream().anyMatch(scope -> scope.intersects(changes.keySet(), Set.of()))) return true;
            if (checkpoint != null && other.sectionScopes.values().stream().anyMatch(checkpoint.scope()::intersects)) return true;
            if (checkpoint != null && other.checkpoint != null) {
                return checkpoint.scope().intersects(other.checkpoint.scope());
            }
            if (checkpoint != null) {
                return checkpoint.scope().intersects(other.changes.keySet(), other.entities.keySet());
            }
            return other.checkpoint != null
                    && other.checkpoint.scope().intersects(changes.keySet(), entities.keySet());
        }

        private final UUID id;
        private final UUID player;
        private final long sequence;
        private UUID groupId;
        private UUID axiomEntry;
        private final Map<BlockPosition, BlockDelta> changes = new LinkedHashMap<>();
        private final Map<UUID, EntityDelta> entities = new LinkedHashMap<>();
        private final Map<BiomePosition, BiomeDelta> biomes = new LinkedHashMap<>();
        private final Map<SectionKey, SectionDelta> sections = new LinkedHashMap<>();
        private final Map<SectionKey, WorldActionScope> sectionScopes = new HashMap<>();
        private boolean closed;
        private boolean started;
        private boolean available = true;
        private boolean applied = true;
        private WorldActionCheckpoint checkpoint;
        private long bytes;
        private long revision;
        private int causalReferences;
        private int delayedReferences;

        private MutableAction(UUID id, UUID player, long sequence) {
            this.id = id;
            this.player = player;
            this.sequence = sequence;
            groupId = id;
        }
    }

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
        private final Map<UUID, Integer> groupMembers = new HashMap<>();

        private void add(UUID group) {
            groupMembers.merge(group, 1, Integer::sum);
        }

        private void remove(UUID group) {
            groupMembers.computeIfPresent(
                    group, (ignored, members) -> members == 1 ? null : members - 1);
        }

        private void mergeGroups(UUID target, UUID source) {
            Integer members = groupMembers.remove(source);
            if (members != null) {
                groupMembers.merge(target, members, Integer::sum);
            }
        }

        private int groupCount() {
            return groupMembers.size();
        }
    }
}
