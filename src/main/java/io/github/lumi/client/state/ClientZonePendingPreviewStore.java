package io.github.lumi.client.state;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.network.ZonePendingPreviewPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Keeps an explicit zone preview separate from workspace history coordinates. */
public final class ClientZonePendingPreviewStore {
    private Context scope;
    private UUID pending;
    private ZonePendingPreviewPayload result;

    public synchronized void begin(UUID request, HistorySnapshotPayload snapshot,
            HistorySnapshotPayload.ZoneView zone) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(zone, "zone");
        var next = new Context(snapshot.dimensionId(), snapshot.workspaceId(), snapshot.head(),
                snapshot.revision(), zone.id(), zone.revision());
        if (!next.equals(scope)) result = null;
        scope = next;
        pending = request;
    }

    public synchronized boolean accept(ZonePendingPreviewPayload update) {
        Objects.requireNonNull(update, "update");
        if (pending == null || !pending.equals(update.requestId()) || !scope.matches(update)) return false;
        pending = null;
        result = update;
        return true;
    }

    public synchronized void reject(OperationEventPayload event) {
        if (Objects.equals(pending, event.requestId())
                && event.state() != OperationEventPayload.State.ACCEPTED
                && event.state() != OperationEventPayload.State.PROGRESS) pending = null;
    }

    public synchronized boolean hasScope() {
        return scope != null;
    }

    public synchronized Optional<HistorySnapshotPayload.ZoneView> currentZone(HistorySnapshotPayload snapshot) {
        if (scope == null || snapshot == null || !scope.dimension().equals(snapshot.dimensionId())
                || !scope.workspace().equals(snapshot.workspaceId()) || !scope.head().equals(snapshot.head())
                || scope.revision() != snapshot.revision()) return Optional.empty();
        return snapshot.zones().stream().filter(zone -> zone.id().equals(scope.zone()) && zone.active()).findFirst();
    }

    public synchronized List<HistorySnapshotPayload.PendingBlock> blocks(HistorySnapshotPayload snapshot) {
        var zone = currentZone(snapshot);
        if (result == null || !result.error().isEmpty() || zone.isEmpty()
                || zone.orElseThrow().revision() != result.zoneRevision()
                || snapshot.pendingRevision() != result.pendingRevision()) return List.of();
        return result.blocks();
    }

    public synchronized void clear() {
        scope = null;
        pending = null;
        result = null;
    }

    private record Context(String dimension, UUID workspace, CommitId head,
            long revision, UUID zone, long zoneRevision) {
        private boolean matches(ZonePendingPreviewPayload value) {
            return dimension.equals(value.dimensionId()) && workspace.equals(value.workspaceId())
                    && head.equals(value.head()) && revision == value.revision()
                    && zone.equals(value.zoneId()) && zoneRevision == value.zoneRevision();
        }
    }
}
