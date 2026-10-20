package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ActionReplayTarget;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.WorldActionIndex;
import io.github.lumi.domain.model.WorldActionRecord;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.model.WorldActionCheckpoint;
import io.github.lumi.domain.model.WorldActionContext;
import io.github.lumi.storage.object.WorldActionIndexCodec;
import io.github.lumi.storage.repository.WorldActionHistoryRepository;
import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Serializes history publications on a worker; no filesystem work belongs on the server tick. */
public final class WorldActionHistory {
    private final WorldActionHistoryRepository repository;
    private final WorldDataVersion version;
    private final UUID epoch;
    private WorldActionIndexCodec.State persisted;

    public WorldActionHistory(WorldActionHistoryRepository repository, WorldDataVersion version) throws IOException {
        this.repository = repository;
        this.version = version;
        persisted = repository.readIndex().orElse(null);
        var nativeIntent = repository.readNativeReplay();
        if (nativeIntent.isPresent() && !nativeIntent.orElseThrow().dataVersion().equals(version)) {
            throw new IOException("Recover interrupted Axiom Undo/Redo with Minecraft data version " + nativeIntent.orElseThrow().dataVersion().value() + " before upgrading");
        }
        if (persisted != null && !persisted.dataVersion().equals(version)) {
            throw new IOException("Undo history requires its original Minecraft data version");
        }
        epoch = persisted == null ? UUID.randomUUID() : persisted.epoch();
    }

    public synchronized void restore(LiveActionJournal journal, UUID workspace, BranchName branch) throws IOException {
        if (persisted == null) return;
        if (!persisted.workspace().equals(workspace) || !persisted.branch().equals(branch)) {
            throw new IOException("Undo history belongs to another active workspace or branch");
        }
        restoreDuringRecovery(journal);
    }

    /** Its existing world journal owns the freeze; pointer publication may be interrupted between selections. */
    public synchronized void restoreDuringRecovery(LiveActionJournal journal) throws IOException {
        if (persisted != null) journal.restore(persisted.index(), metadata -> readRecord(persisted, metadata));
    }

    public synchronized WorldActionRecord readRecord(WorldActionIndexCodec.State state, WorldActionRecord.Metadata metadata) throws IOException {
        return metadata.checkpoint().isPresent() ? new WorldActionRecord(metadata, new WorldActionRecord.Payload(Map.of(), Map.of()))
                : repository.readPayload(metadata, state.payloads().get(metadata.id()), version, 128L << 20);
    }

    /** An edit that changes during capture retries; the previous durable root remains untouched. */
    public synchronized boolean flush(UUID workspace, BranchName branch, WorldActionIndex index,
            LiveActionJournal.ActionSource source, BooleanSupplier current) throws IOException {
        if (persisted != null && index.revision() < persisted.index().revision()) return false;
        if (persisted != null && persisted.workspace().equals(workspace) && persisted.branch().equals(branch)
                && persisted.index().equals(index)) return current.getAsBoolean();
        try (var publication = repository.beginPublication()) {
            var blobs = new HashMap<UUID, UUID>();
            var previous = new HashMap<UUID, WorldActionRecord.Metadata>();
            if (persisted != null) persisted.index().actions().forEach(action -> previous.put(action.id(), action));
            try {
                for (var metadata : index.actions()) {
                    if (metadata.checkpoint().isPresent()) {
                        source.read(metadata); // Validate new endpoint ownership while GC is excluded.
                        continue;
                    }
                    var old = previous.get(metadata.id());
                    blobs.put(metadata.id(), old != null && old.revision() == metadata.revision()
                            ? persisted.payloads().get(metadata.id()) : repository.writePayload(source.read(metadata), version));
                }
            } catch (ConcurrentModificationException changed) {
                return false;
            }
            if (!current.getAsBoolean()) return false;
            var next = new WorldActionIndexCodec.State(epoch, version, workspace, branch, index, blobs,
                    persisted == null ? Optional.empty() : persisted.publication());
            repository.writeIndex(next);
            persisted = next;
            repository.removeUnreferencedPayloads();
            return true;
        }
    }

    /** Runs inside the existing journaled apply publication, before its journal may be removed. */
    public synchronized void publishReplay(UUID operation, ActionReplayTarget step, boolean applied) throws IOException {
        if (persisted == null) throw new IOException("The selected action has not been stored");
        var marker = new WorldActionIndexCodec.Publication(operation, step, applied);
        if (persisted.publication().filter(marker::equals).isPresent()) { clearPublishedNativeReplay(operation); return; }
        final WorldActionIndex index;
        try {
            index = persisted.index().move(step, applied);
        } catch (IllegalStateException conflict) {
            throw new IOException("Undo cursor no longer matches the journaled action", conflict);
        }
        var context = persisted.index().actions().stream().filter(action -> action.id().equals(step.action()))
                .findFirst().orElseThrow().checkpoint().flatMap(WorldActionCheckpoint::context)
                .map(change -> change.endpoint(applied)).orElse(new WorldActionContext(persisted.workspace(), persisted.branch()));
        var next = new WorldActionIndexCodec.State(epoch, version, context.workspace(), context.branch(),
                index, persisted.payloads(), Optional.of(marker));
        repository.writeIndex(next);
        persisted = next;
        clearPublishedNativeReplay(operation);
    }

    private void clearPublishedNativeReplay(UUID operation) {
        try {
            var intent = repository.readNativeReplay();
            if (intent.isPresent() && intent.orElseThrow().publication().orElseThrow().operation().equals(operation)) repository.clearNativeReplay(operation);
        } catch (IOException failed) {
            // World and cursor are already durable; startup retries this idempotent cleanup.
            java.util.logging.Logger.getLogger(WorldActionHistory.class.getName()).log(java.util.logging.Level.WARNING,
                    "Cannot collect published native replay intent", failed);
        }
    }

    public synchronized void beginNativeReplay(UUID operation, LiveActionJournal.Plan plan) throws IOException {
        beginNativeReplay(operation, plan, plan);
    }

    public synchronized void beginNativeReplay(UUID operation, LiveActionJournal.Plan plan, LiveActionJournal.Plan recovery) throws IOException {
        beginNativeReplay(operation, plan, recovery, false);
    }

    public synchronized void beginNativeReplay(UUID operation, LiveActionJournal.Plan plan, LiveActionJournal.Plan recovery, boolean selective) throws IOException {
        if (!plan.player().equals(recovery.player()) || !plan.actionIds().equals(recovery.actionIds()) || plan.direction() != recovery.direction())
            throw new IllegalArgumentException("Recovery belongs to another action");
        if (persisted == null) throw new IOException("The selected action has not been stored");
        boolean undo = plan.direction() == LiveActionJournal.Direction.UNDO;
        var step = new ActionReplayTarget(plan.player(), plan.actionId(), undo, selective);
        // Validate the durable cursor before making an intent reachable.
        persisted.index().move(step, !undo);
        pendingNativeReplay(); // Collect an already-published intent left by interrupted cleanup.
        try (var publication = repository.beginPublication()) {
            var index = persisted.index();
            var payloads = persisted.payloads();
            if (!recovery.equals(plan)) {
                if (recovery.sections().isEmpty() || !recovery.expected().isEmpty() || !recovery.expectedEntities().isEmpty()
                        || !recovery.biomes().isEmpty() || recovery.checkpoint().isPresent()) throw new IllegalArgumentException("Not a section recovery plan");
                var selected = index.actions().stream().filter(action -> action.id().equals(plan.actionId())).findFirst().orElseThrow();
                var payload = new WorldActionRecord.Payload(Map.of(), Map.of(), Map.of(), recovery.sections());
                var blob = repository.writePayload(new WorldActionRecord(selected, payload), version);
                index = new WorldActionIndex(index.revision(), index.nextSequence(), index.unsafeBeforeSequence(), java.util.List.of(selected),
                        Map.of(plan.player(), new WorldActionIndex.Stacks(undo ? java.util.List.of(selected.id()) : java.util.List.of(),
                                undo ? java.util.List.of() : java.util.List.of(selected.id()))));
                payloads = Map.of(selected.id(), blob);
            }
            repository.writeNativeReplay(new WorldActionIndexCodec.State(epoch, version, persisted.workspace(),
                    persisted.branch(), index, payloads, Optional.of(new WorldActionIndexCodec.Publication(operation, step, !undo))));
        }
    }

    public synchronized Optional<WorldActionIndexCodec.State> pendingNativeReplay() throws IOException {
        var intent = repository.readNativeReplay();
        if (intent.isPresent() && persisted != null && persisted.confirmsReplay(intent.orElseThrow())) {
            repository.clearNativeReplay(intent.orElseThrow().publication().orElseThrow().operation());
            return Optional.empty();
        }
        return intent;
    }

    public synchronized LiveActionJournal.Plan nativeReplayPlan(WorldActionIndexCodec.State intent) throws IOException {
        if (!intent.epoch().equals(epoch) || !intent.dataVersion().equals(version)) throw new IOException("Native replay context changed");
        var marker = intent.publication().orElseThrow();
        var step = marker.step();
        var index = intent.index();
        index.move(step, !step.undo());
        var selected = index.actions().stream().filter(action -> action.id().equals(step.action()) && action.actor().equals(step.actor()))
                .findFirst().orElseThrow(() -> new IOException("Native replay action is missing"));
        var group = index.actions().stream().filter(action -> action.group().equals(selected.group())).toList();
        var ids = group.stream().map(WorldActionRecord.Metadata::id).collect(java.util.stream.Collectors.toSet());
        var stacks = index.players().get(step.actor());
        var replayIndex = new WorldActionIndex(index.revision(), index.nextSequence(), index.unsafeBeforeSequence(), group,
                Map.of(step.actor(), new WorldActionIndex.Stacks(stacks.undo().stream().filter(ids::contains).toList(),
                        stacks.redo().stream().filter(ids::contains).toList())));
        var journal = new LiveActionJournal();
        journal.restore(replayIndex, metadata -> readRecord(intent, metadata));
        var plan = (step.undo() ? journal.prepareUndo(step.actor()) : journal.prepareRedo(step.actor()))
                .orElseThrow(() -> new IOException("Native replay action is missing"));
        if (!plan.actionId().equals(step.action())) throw new IOException("Native replay cursor changed");
        return plan;
    }

    public synchronized void clearNativeReplay(UUID operation) throws IOException { repository.clearNativeReplay(operation); }

    public synchronized ActionReplayTarget prepareCheckpoint(UUID operation, UUID actor, WorldActionCheckpoint checkpoint,
            UUID workspace, BranchName branch) throws IOException {
        if (persisted == null) throw new IOException("History must be captured before preparing a replacement action");
        var currentContext = new WorldActionContext(persisted.workspace(), persisted.branch());
        if (checkpoint.context().isPresent() && !checkpoint.context().orElseThrow().before().equals(currentContext)) {
            throw new IOException("Active context changed before recording its switch");
        }
        var index = persisted.index().appendCheckpoint(operation, actor, checkpoint, LiveActionJournal.Limits.DEFAULT.maxActionsPerPlayer());
        var blobs = retainedPayloads(index);
        var context = checkpoint.context().map(WorldActionContext.Change::after).orElse(new WorldActionContext(workspace, branch));
        var next = new WorldActionIndexCodec.State(epoch, version, context.workspace(), context.branch(), index, blobs, persisted.publication());
        // A new dimension mutation can start only after its predecessor's journal was resolved.
        repository.pruneStagedCheckpoints(Optional.of(operation));
        repository.stageCheckpoint(operation, persisted, next);
        return new ActionReplayTarget(actor, operation, false);
    }

    public synchronized WorldActionIndexCodec.State publishCheckpoint(UUID operation, boolean applied) throws IOException {
        var next = repository.stagedCheckpoint(operation, applied);
        var after = repository.stagedCheckpoint(operation, true);
        var action = after.index().actions().stream().filter(value -> value.id().equals(operation)).findFirst().orElseThrow();
        var marker = new WorldActionIndexCodec.Publication(operation, new ActionReplayTarget(action.actor(), operation, false), applied);
        if (persisted != null && persisted.publication().filter(marker::equals).isPresent()) return persisted;
        if (!next.epoch().equals(epoch)) throw new IOException("Prepared action belongs to another history epoch");
        var old = next.index();
        var index = new WorldActionIndex(Math.max(old.revision(), persisted == null ? 0 : persisted.index().revision()) + 1,
                old.nextSequence(), old.unsafeBeforeSequence(), old.actions(), old.players());
        next = new WorldActionIndexCodec.State(epoch, version, next.workspace(), next.branch(), index, next.payloads(), Optional.of(marker));
        repository.writeIndex(next);
        persisted = next;
        return next;
    }

    public synchronized boolean hasPreparedCheckpoint(UUID operation) { return repository.hasStagedCheckpoint(operation); }
    /** Metadata-only publication: no world data or other builder's cursor is changed. */
    public synchronized WorldActionIndexCodec.State forget(UUID actor) throws IOException {
        if (persisted == null) throw new IOException("History has not been stored");
        var index = persisted.index().forget(actor);
        var blobs = retainedPayloads(index);
        var next = new WorldActionIndexCodec.State(epoch, version, persisted.workspace(), persisted.branch(), index, blobs, persisted.publication());
        repository.pruneStagedCheckpoints(Optional.empty());
        repository.writeIndex(next);
        persisted = next;
        return next;
    }
    public UUID epoch() { return epoch; }

    private Map<UUID, UUID> retainedPayloads(WorldActionIndex index) {
        var blobs = new HashMap<>(persisted.payloads());
        var retained = index.actions().stream().map(WorldActionRecord.Metadata::id).collect(java.util.stream.Collectors.toSet());
        blobs.keySet().retainAll(retained);
        return blobs;
    }
}
