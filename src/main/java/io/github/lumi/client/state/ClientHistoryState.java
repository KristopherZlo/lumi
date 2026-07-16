package io.github.lumi.client.state;

import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable client view; it contains no world or repository ownership. */
public record ClientHistoryState(
        Optional<HistorySnapshotPayload> snapshot,
        Map<UUID, OperationEventPayload> events) {
    public ClientHistoryState {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        events = Map.copyOf(Objects.requireNonNull(events, "events"));
    }

    public static ClientHistoryState empty() {
        return new ClientHistoryState(Optional.empty(), Map.of());
    }

    public Optional<OperationEventPayload> activeOperation() {
        return events.values().stream()
                .filter(event -> event.state() == OperationEventPayload.State.ACCEPTED
                        || event.state() == OperationEventPayload.State.PROGRESS)
                .min(java.util.Comparator.comparingInt(OperationEventPayload::queuePosition));
    }
}
