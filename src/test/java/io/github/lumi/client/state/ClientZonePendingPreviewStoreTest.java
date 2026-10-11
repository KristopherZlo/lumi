package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.ZonePendingPreviewPayload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientZonePendingPreviewStoreTest {
    private static final UUID WORKSPACE = new UUID(0, 1);
    private static final UUID ZONE = new UUID(0, 2);
    private static final UUID FIRST = new UUID(0, 3);
    private static final UUID SECOND = new UUID(0, 4);
    private static final CommitId HEAD = CommitId.hash(new byte[] {1});
    private static final HistorySnapshotPayload.PendingBlock INSIDE =
            new HistorySnapshotPayload.PendingBlock(1, 2, 3);

    @Test
    void acceptsOnlyTheLatestResponseForTheRequestedZone() {
        var store = new ClientZonePendingPreviewStore();
        var snapshot = snapshot(WORKSPACE, 4, 2);
        store.begin(FIRST, snapshot, snapshot.zones().getFirst());
        store.begin(SECOND, snapshot, snapshot.zones().getFirst());
        assertFalse(store.accept(result(FIRST, ZONE, 4, "")));
        assertFalse(store.accept(result(SECOND, new UUID(0, 9), 4, "")));
        assertTrue(store.accept(result(SECOND, ZONE, 4, "")));
        assertEquals(List.of(INSIDE), store.blocks(snapshot));
    }

    @Test
    void hidesStaleGeometryAndRetainsZoneScopeWhileAReplacementIsPending() {
        var store = new ClientZonePendingPreviewStore();
        var before = snapshot(WORKSPACE, 4, 2);
        store.begin(FIRST, before, before.zones().getFirst());
        assertTrue(store.accept(result(FIRST, ZONE, 4, "")));
        var changed = snapshot(WORKSPACE, 5, 2);
        assertTrue(store.blocks(changed).isEmpty());
        store.begin(SECOND, changed, changed.zones().getFirst());
        assertTrue(store.hasScope());
        assertTrue(store.blocks(changed).isEmpty());
        assertTrue(store.accept(result(SECOND, ZONE, 5, "")));
        assertEquals(List.of(INSIDE), store.blocks(changed));
    }

    @Test
    void failureNeverSubstitutesWorkspaceCoordinates() {
        var store = new ClientZonePendingPreviewStore();
        var snapshot = snapshot(WORKSPACE, 4, 2);
        store.begin(FIRST, snapshot, snapshot.zones().getFirst());
        assertTrue(store.accept(result(FIRST, ZONE, 4, "Failed")));
        assertTrue(store.hasScope());
        assertTrue(store.blocks(snapshot).isEmpty());
    }

    @Test
    void workspaceOrZoneRevisionChangesHidePreviousGeometry() {
        var store = new ClientZonePendingPreviewStore();
        var snapshot = snapshot(WORKSPACE, 4, 2);
        store.begin(FIRST, snapshot, snapshot.zones().getFirst());
        store.accept(result(FIRST, ZONE, 4, ""));
        assertTrue(store.blocks(snapshot(WORKSPACE, 4, 3)).isEmpty());
        assertTrue(store.currentZone(snapshot(new UUID(0, 8), 4, 2)).isEmpty());
        store.clear();
        assertFalse(store.hasScope());
    }

    private static HistorySnapshotPayload snapshot(UUID workspace, long pending, long zoneRevision) {
        return new HistorySnapshotPayload("minecraft:overworld", HEAD, 0, 1, pending,
                List.of(new HistorySnapshotPayload.PendingBlock(100, 2, 3)), Optional.empty(),
                false, false, workspace, "Build", "main", List.of(), List.of(), List.of(),
                List.of(new HistorySnapshotPayload.ZoneView(ZONE, "Zone", 0, 1, zoneRevision, true)), List.of());
    }

    private static ZonePendingPreviewPayload result(UUID request, UUID zone, long pending, String error) {
        return new ZonePendingPreviewPayload(request, "minecraft:overworld", WORKSPACE, HEAD,
                0, zone, 2, pending, error.isEmpty() ? List.of(INSIDE) : List.of(), error);
    }
}
