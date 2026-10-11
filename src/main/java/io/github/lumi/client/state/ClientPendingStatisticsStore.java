package io.github.lumi.client.state;

import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.PendingStatisticsPayload;
import java.util.Objects;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.UUID;

/** Owns the latest correlated pending-statistics request and immutable result. */
public final class ClientPendingStatisticsStore {
    private Pending pending;
    private PendingStatisticsPayload result;
    private Map<UUID, Long> resultZoneRevisions = Map.of();

    public synchronized void begin(
            UUID requestId, HistorySnapshotPayload snapshot) {
        Objects.requireNonNull(requestId, "requestId");
        HistorySnapshotPayload current = Objects.requireNonNull(
                snapshot, "snapshot");
        pending = new Pending(
                requestId, current.dimensionId(), current.workspaceId(),
                current.head(), current.revision(), current.pendingRevision(),
                current.zones().stream().collect(Collectors.toUnmodifiableMap(
                        HistorySnapshotPayload.ZoneView::id, HistorySnapshotPayload.ZoneView::revision)));
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
        resultZoneRevisions = pending.zoneRevisions();
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
                && pending.pendingRevision() == current.pendingRevision()
                && sameZones(pending.zoneRevisions(), current);
    }

    public synchronized boolean needsRequest(HistorySnapshotPayload snapshot) {
        Optional<PendingStatisticsPayload> accepted = result(snapshot);
        return !pending(snapshot)
                && (accepted.isEmpty()
                        || accepted.orElseThrow().pendingRevision()
                                != snapshot.pendingRevision()
                        || !sameZones(resultZoneRevisions, snapshot));
    }

    public synchronized void clear() {
        pending = null;
        result = null;
        resultZoneRevisions = Map.of();
    }

    public synchronized Optional<PendingStatisticsPayload> zoneResult(HistorySnapshotPayload snapshot, UUID zoneId) {
        return result(snapshot).filter(value -> value.error().isEmpty()
                && value.pendingRevision() == snapshot.pendingRevision()
                && snapshot.zones().stream().anyMatch(zone -> zone.id().equals(zoneId)
                        && Objects.equals(resultZoneRevisions.get(zoneId), zone.revision())));
    }

    public synchronized Optional<Boolean> zoneHasChanges(HistorySnapshotPayload snapshot, UUID zoneId) {
        if (snapshot.pendingKeys() == 0) return Optional.of(false);
        return zoneResult(snapshot, zoneId).map(value ->
                value.zones().getOrDefault(zoneId, io.github.lumi.domain.model.PendingChangeStatistics.NONE).total() > 0
                        || value.zonesWithPendingEntities().contains(zoneId));
    }

    private static boolean sameZones(Map<UUID, Long> revisions, HistorySnapshotPayload snapshot) {
        return revisions.size() == snapshot.zones().size() && snapshot.zones().stream()
                .allMatch(zone -> Objects.equals(revisions.get(zone.id()), zone.revision()));
    }

    private record Pending(
            UUID requestId,
            String dimensionId,
            UUID workspaceId,
            io.github.lumi.domain.model.CommitId head,
            long revision,
            long pendingRevision, Map<UUID, Long> zoneRevisions) { }
}
