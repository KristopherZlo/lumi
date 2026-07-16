package io.github.lumi.client.state;

import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded mutable owner for immutable snapshots received from the server. */
public final class ClientHistoryStore {
    private static final int MAX_EVENTS = 64;
    private HistorySnapshotPayload snapshot;
    private final LinkedHashMap<UUID, OperationEventPayload> events = new LinkedHashMap<>();

    public synchronized void accept(HistorySnapshotPayload update) {
        Objects.requireNonNull(update, "update");
        if (snapshot == null || !snapshot.dimensionId().equals(update.dimensionId())) {
            events.clear();
        }
        snapshot = update;
    }

    public synchronized void accept(OperationEventPayload event) {
        Objects.requireNonNull(event, "event");
        if (snapshot == null || !snapshot.dimensionId().equals(event.dimensionId())) {
            return;
        }
        events.remove(event.requestId());
        events.put(event.requestId(), event);
        while (events.size() > MAX_EVENTS) {
            events.remove(events.keySet().iterator().next());
        }
        snapshot = new HistorySnapshotPayload(
                snapshot.dimensionId(), event.head(), event.revision(), snapshot.pendingKeys(),
                snapshot.pendingBlocks(),
                events.values().stream().anyMatch(value ->
                        value.state() == OperationEventPayload.State.ACCEPTED
                                || value.state() == OperationEventPayload.State.PROGRESS),
                snapshot.recoveryPending(),
                snapshot.workspaceId(), snapshot.workspaceName(), snapshot.branchName(),
                snapshot.workspaces(), snapshot.versions(), snapshot.branches(), snapshot.zones(),
                snapshot.deletedVersions());
    }

    public synchronized ClientHistoryState state() {
        return new ClientHistoryState(Optional.ofNullable(snapshot), events);
    }

    public synchronized void clear() {
        snapshot = null;
        events.clear();
    }
}
