package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ActionReplayTarget;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.WorldActionIndex;
import io.github.lumi.domain.model.WorldActionRecord;
import io.github.lumi.domain.model.WorldDataVersion;
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
        journal.restore(persisted.index(), metadata -> metadata.checkpoint().isPresent()
                ? new WorldActionRecord(metadata, new WorldActionRecord.Payload(Map.of(), Map.of()))
                : repository.readPayload(metadata, persisted.payloads().get(metadata.id()), version, 128L << 20));
    }

    /** An edit that changes during capture retries; the previous durable root remains untouched. */
    public synchronized boolean flush(UUID workspace, BranchName branch, WorldActionIndex index,
            LiveActionJournal.ActionSource source, BooleanSupplier current) throws IOException {
        if (persisted != null && index.revision() < persisted.index().revision()) return false;
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
        if (persisted.publication().filter(marker::equals).isPresent()) return;
        final WorldActionIndex index;
        try {
            index = persisted.index().move(step, applied);
        } catch (IllegalStateException conflict) {
            throw new IOException("Undo cursor no longer matches the journaled action", conflict);
        }
        var next = new WorldActionIndexCodec.State(epoch, version, persisted.workspace(), persisted.branch(),
                index, persisted.payloads(), Optional.of(marker));
        repository.writeIndex(next);
        persisted = next;
    }
}
