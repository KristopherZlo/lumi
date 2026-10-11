package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryState;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Correlates one zone Enter with terminal refusal and authoritative activation. */
public final class ZoneEntryController {
    private final Function<UUID, UUID> enter;
    private Pending pending;

    public ZoneEntryController(Function<UUID, UUID> enter) {
        this.enter = Objects.requireNonNull(enter, "enter");
    }

    public void begin(HistorySnapshotPayload snapshot, UUID zoneId) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(zoneId, "zoneId");
        if (pending != null) throw new IllegalStateException("A zone Enter is pending");
        UUID request = Objects.requireNonNull(enter.apply(zoneId), "requestId");
        pending = new Pending(request, zoneId, snapshot.dimensionId(), snapshot.workspaceId());
    }

    public boolean pending() {
        return pending != null;
    }

    public Update poll(ClientHistoryState state) {
        Objects.requireNonNull(state, "state");
        if (pending == null) return Update.UNCHANGED;
        HistorySnapshotPayload snapshot = state.snapshot().orElse(null);
        if (snapshot == null || !pending.dimension().equals(snapshot.dimensionId())
                || !pending.workspace().equals(snapshot.workspaceId())) {
            pending = null;
            return new Update(Optional.empty(), "Zone context changed; reopen the zone", true);
        }
        OperationEventPayload event = state.events().get(pending.request());
        if (event != null && event.state() != OperationEventPayload.State.ACCEPTED
                && event.state() != OperationEventPayload.State.PROGRESS
                && event.state() != OperationEventPayload.State.SUCCEEDED) {
            pending = null;
            return new Update(Optional.empty(), event.message().isBlank()
                    ? "Lumi zone could not be entered" : event.message(), true);
        }
        if (event == null || event.state() != OperationEventPayload.State.SUCCEEDED) {
            return Update.UNCHANGED;
        }
        Optional<HistorySnapshotPayload.ZoneView> entered = snapshot.zones().stream()
                .filter(zone -> zone.id().equals(pending.zone()) && zone.active())
                .findFirst();
        if (entered.isEmpty()) return Update.UNCHANGED;
        pending = null;
        return new Update(entered, "", true);
    }

    public record Update(
            Optional<HistorySnapshotPayload.ZoneView> entered, String error, boolean changed) {
        private static final Update UNCHANGED = new Update(Optional.empty(), "", false);
        public Update {
            Objects.requireNonNull(entered, "entered");
            Objects.requireNonNull(error, "error");
        }
    }

    private record Pending(UUID request, UUID zone, String dimension, UUID workspace) { }
}
