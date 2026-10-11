package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PendingChangeStatistics;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.PendingStatisticsPayload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZoneSaveAvailabilityTest {
    private final UUID zone = new UUID(0, 1);
    private final UUID workspace = new UUID(0, 2);
    private final ClientPendingStatisticsStore statistics = new ClientPendingStatisticsStore();

    @Test
    void distinguishesUnknownScopeFromCleanScopeAndInactiveZone() {
        var snapshot = snapshot(true, 1);
        assertEquals("luma.dashboard.pending_calculating", availability(snapshot).saveReason());
        accept(snapshot, PendingChangeStatistics.NONE, Set.of());
        assertEquals("luma.zones.pending_clean", availability(snapshot).saveReason());
        assertEquals("luma.zones.details_inactive", availability(snapshot(false, 1)).saveReason());
        assertEquals("luma.zones.pending_clean", availability(snapshot(true, 0)).saveReason());
    }

    @Test
    void exposesAStatisticsFailureAndRetriesOnceWhenReopened() {
        var snapshot = snapshot(true, 1);
        UUID request = UUID.randomUUID();
        statistics.begin(request, snapshot);
        statistics.accept(new PendingStatisticsPayload(request, snapshot.dimensionId(), workspace,
                snapshot.head(), 0, 1, PendingChangeStatistics.NONE, Map.of(), "Capture unavailable"));
        assertEquals("Capture unavailable", availability(snapshot).saveReason());
        assertEquals(false, statistics.needsRequest(snapshot));
        assertEquals(true, statistics.needsRequestOnOpen(snapshot));
        statistics.begin(UUID.randomUUID(), snapshot);
        assertEquals(false, statistics.needsRequestOnOpen(snapshot));
        assertEquals("luma.dashboard.pending_calculating", availability(snapshot).saveReason());
    }

    @Test
    void entityOnlyChangesAllowSaveWhileAmendStillRequiresAZoneVersion() {
        var snapshot = snapshot(true, 1);
        accept(snapshot, PendingChangeStatistics.NONE, Set.of(zone));
        assertEquals("", availability(snapshot).saveReason());
        assertEquals("luma.zones.history_empty", availability(snapshot).amendReason());
        accept(snapshot, new PendingChangeStatistics(1, 0, 0), Set.of());
        assertEquals("", availability(snapshot).saveReason());
    }

    private ZoneSaveAvailability availability(HistorySnapshotPayload snapshot) {
        return ZoneSaveAvailability.inspect(snapshot, zone, statistics);
    }

    private void accept(HistorySnapshotPayload snapshot, PendingChangeStatistics blocks, Set<UUID> entities) {
        UUID request = UUID.randomUUID();
        statistics.begin(request, snapshot);
        statistics.accept(new PendingStatisticsPayload(request, snapshot.dimensionId(), workspace,
                snapshot.head(), 0, 1, new PendingChangeStatistics(1, 0, 0), Map.of(zone, blocks), entities, ""));
    }

    private HistorySnapshotPayload snapshot(boolean active, int pendingKeys) {
        return new HistorySnapshotPayload("minecraft:overworld", new CommitId(new ObjectId("a".repeat(64))),
                0, pendingKeys, 1, List.of(), Optional.empty(), false, false, workspace, "Build", "main",
                List.of(), List.of(), List.of(),
                List.of(new HistorySnapshotPayload.ZoneView(zone, "Zone", 0, 1, 0, active)), List.of());
    }
}
