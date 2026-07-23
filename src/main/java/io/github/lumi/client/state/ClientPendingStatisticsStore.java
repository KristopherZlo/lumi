package io.github.lumi.client.state;

import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.PendingStatisticsPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns the latest correlated pending-statistics request and immutable result. */
public final class ClientPendingStatisticsStore {
    private Pending pending;
    private PendingStatisticsPayload result;

    public synchronized void begin(
            UUID requestId, HistorySnapshotPayload snapshot) {
        Objects.requireNonNull(requestId, "requestId");
        HistorySnapshotPayload current = Objects.requireNonNull(
                snapshot, "snapshot");
        pending = new Pending(
                requestId, current.dimensionId(), current.workspaceId(),
                current.head(), current.revision(), current.pendingRevision());
    }

    public synchronized boolean accept(PendingStatisticsPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (pending == null
                || !pending.requestId().equals(payload.requestId())
                || !pending.dimensionId().equals(payload.dimensionId())
                || !pending.workspaceId().equals(payload.workspaceId())
                || !pending.head().equals(payload.head())
                || pending.revision() != payload.revision()
                || pending.pendingRevision() != payload.pendingRevision()) {
            return false;
        }
        pending = null;
        result = payload;
        return true;
    }

    public synchronized Optional<PendingStatisticsPayload> result(
            HistorySnapshotPayload snapshot) {
        HistorySnapshotPayload current = Objects.requireNonNull(
                snapshot, "snapshot");
        if (result == null
                || !result.dimensionId().equals(current.dimensionId())
                || !result.workspaceId().equals(current.workspaceId())
                || !result.head().equals(current.head())
                || result.revision() != current.revision()) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    public synchronized boolean pending(HistorySnapshotPayload snapshot) {
        HistorySnapshotPayload current = Objects.requireNonNull(
                snapshot, "snapshot");
        return pending != null
                && pending.dimensionId().equals(current.dimensionId())
                && pending.workspaceId().equals(current.workspaceId())
                && pending.head().equals(current.head())
                && pending.revision() == current.revision()
                && pending.pendingRevision() == current.pendingRevision();
    }

    public synchronized boolean needsRequest(HistorySnapshotPayload snapshot) {
        Optional<PendingStatisticsPayload> accepted = result(snapshot);
        return !pending(snapshot)
                && (accepted.isEmpty()
                        || accepted.orElseThrow().pendingRevision()
                                != snapshot.pendingRevision());
    }

    public synchronized void clear() {
        pending = null;
        result = null;
    }

    private record Pending(
            UUID requestId,
            String dimensionId,
            UUID workspaceId,
            io.github.lumi.domain.model.CommitId head,
            long revision,
            long pendingRevision) { }
}
