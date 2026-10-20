package io.github.lumi.client.state;

import io.github.lumi.network.AxiomHistorySettingsPayload;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.network.WorldActionViewPayload;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded mutable owner for immutable snapshots received from the server. */
public final class ClientHistoryStore {
    private static final int MAX_EVENTS = 64;
    private HistorySnapshotPayload snapshot;
    private WorldActionViewPayload worldActions;
    private AxiomHistorySettingsPayload axiomSettings;

    public synchronized void accept(AxiomHistorySettingsPayload settings) { axiomSettings = settings; }
    public synchronized Optional<AxiomHistorySettingsPayload> axiomSettings() { return Optional.ofNullable(axiomSettings); }
    private UUID worldActionRequest;
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
        if (event.requestId().equals(worldActionRequest)
                && event.state() != OperationEventPayload.State.ACCEPTED
                && event.state() != OperationEventPayload.State.PROGRESS
                && event.state() != OperationEventPayload.State.SUCCEEDED) worldActionRequest = null;
        events.remove(event.requestId());
        events.put(event.requestId(), event);
        while (events.size() > MAX_EVENTS) {
            events.remove(events.keySet().iterator().next());
        }
        boolean interim = event.state() == OperationEventPayload.State.ACCEPTED
                || event.state() == OperationEventPayload.State.PROGRESS;
        snapshot = new HistorySnapshotPayload(
                snapshot.dimensionId(), interim ? snapshot.head() : event.head(),
                interim ? snapshot.revision() : event.revision(), snapshot.pendingKeys(),
                snapshot.pendingRevision(), snapshot.pendingBlocks(),
                snapshot.pendingBounds(),
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

    public synchronized void accept(WorldActionViewPayload update) {
        if (worldActions != null && worldActions.epoch().equals(update.epoch()) && worldActions.dimension().equals(update.dimension())) {
            if (update.view().revision() < worldActions.view().revision()) return;
        } else worldActionRequest = null;
        worldActions = update;
        if (update.completedRequest().filter(id -> id.equals(worldActionRequest)).isPresent()) worldActionRequest = null;
    }

    public synchronized Optional<WorldActionViewPayload> worldActions() { return Optional.ofNullable(worldActions); }
    public synchronized Optional<UUID> worldActionRequest() { return Optional.ofNullable(worldActionRequest); }
    public synchronized void beginWorldAction(UUID request) {
        if (worldActionRequest != null) throw new IllegalStateException("World action is awaiting confirmation");
        worldActionRequest = Objects.requireNonNull(request);
    }

    public synchronized void clear() {
        snapshot = null;
        worldActions = null;
        axiomSettings = null;
        worldActionRequest = null;
        events.clear();
    }
}
