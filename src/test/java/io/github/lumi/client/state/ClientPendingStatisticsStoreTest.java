package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PendingChangeStatistics;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.PendingStatisticsPayload;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPendingStatisticsStoreTest {
    private static final UUID WORKSPACE = new UUID(0, 1);

    @Test
    void acceptsOnlyTheLatestRequestForTheCurrentHead() {
        ClientPendingStatisticsStore store =
                new ClientPendingStatisticsStore();
        HistorySnapshotPayload snapshot = snapshot('1', 2);
        UUID stale = new UUID(0, 2);
        UUID latest = new UUID(0, 3);
        store.begin(stale, snapshot);
        store.begin(latest, snapshot);

        assertFalse(store.accept(result(stale, snapshot)));
        assertTrue(store.accept(result(latest, snapshot)));
        assertTrue(store.result(snapshot).isPresent());
    }

    @Test
    void hidesAResultAfterTheHistoryContextChanges() {
        ClientPendingStatisticsStore store =
                new ClientPendingStatisticsStore();
        HistorySnapshotPayload snapshot = snapshot('1', 2);
        UUID request = new UUID(0, 4);
        store.begin(request, snapshot);
        assertTrue(store.accept(result(request, snapshot)));

        assertTrue(store.result(snapshot).isPresent());
        assertTrue(store.result(snapshot('2', 3)).isEmpty());
    }

    @Test
    void keepsTheLastNumberVisibleWhileItsReplacementLoads() {
        ClientPendingStatisticsStore store = new ClientPendingStatisticsStore();
        HistorySnapshotPayload snapshot = snapshot('1', 2);
        UUID first = new UUID(0, 5);
        store.begin(first, snapshot);
        assertTrue(store.accept(result(first, snapshot)));

        store.begin(new UUID(0, 6), snapshot);

        assertTrue(store.result(snapshot).isPresent());
        assertTrue(store.pending(snapshot));
    }

    @Test
    void requestsOnlyWhenTheCurrentContextHasNoRequestOrResult() {
        ClientPendingStatisticsStore store = new ClientPendingStatisticsStore();
        HistorySnapshotPayload snapshot = snapshot('1', 2);
        UUID request = new UUID(0, 7);

        assertTrue(store.needsRequest(snapshot));
        store.begin(request, snapshot);
        assertFalse(store.needsRequest(snapshot));
        assertTrue(store.accept(result(request, snapshot)));
        assertFalse(store.needsRequest(snapshot));
        assertTrue(store.needsRequest(snapshot('2', 3)));
    }

    @Test
    void replacesAnExactResultWhenTheBuilderRevisionAdvances() {
        ClientPendingStatisticsStore store = new ClientPendingStatisticsStore();
        HistorySnapshotPayload before = snapshot('1', 2, 4);
        UUID first = new UUID(0, 8);
        store.begin(first, before);
        assertTrue(store.accept(result(first, before)));

        HistorySnapshotPayload changed = snapshot('1', 2, 5);
        assertTrue(store.result(changed).isPresent());
        assertTrue(store.needsRequest(changed));

        UUID replacement = new UUID(0, 9);
        store.begin(replacement, changed);
        assertTrue(store.result(changed).isPresent());
        assertFalse(store.needsRequest(changed));
        assertFalse(store.accept(result(first, before)));
        assertTrue(store.accept(result(replacement, changed)));
    }

    private static PendingStatisticsPayload result(
            UUID request, HistorySnapshotPayload snapshot) {
        return new PendingStatisticsPayload(
                request, snapshot.dimensionId(), snapshot.workspaceId(),
                snapshot.head(), snapshot.revision(),
                snapshot.pendingRevision(),
                new PendingChangeStatistics(3, 2, 1), Map.of(), "");
    }

    private static HistorySnapshotPayload snapshot(char head, long revision) {
        return snapshot(head, revision, 0);
    }

    private static HistorySnapshotPayload snapshot(
            char head, long revision, long pendingRevision) {
        return new HistorySnapshotPayload(
                "minecraft:overworld",
                new CommitId(new ObjectId(String.valueOf(head).repeat(64))),
                revision, 1, pendingRevision, List.of(),
                java.util.Optional.empty(), false, false, WORKSPACE,
                "Build", "main", List.of(), List.of(), List.of(), List.of(),
                List.of());
    }
}
