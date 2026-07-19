package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkspaceHistoryControllerTest {
    @Test
    void advancesByTheActualVisiblePageSize() {
        HistorySnapshotPayload snapshot = snapshot();
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        AtomicInteger requestedOffset = new AtomicInteger();
        UUID request = new UUID(0, 8);
        WorkspaceHistoryController controller = new WorkspaceHistoryController(
                snapshot, pages, (branch, zone, offset, limit) -> {
                    requestedOffset.set(offset);
                    pages.begin(request, snapshot.dimensionId(),
                            snapshot.workspaceId(), branch, zone, offset);
                    return request;
                });
        controller.ensurePageSize(7);
        assertTrue(pages.accept(new HistoryPagePayload(
                request, snapshot.dimensionId(), snapshot.workspaceId(),
                new BranchName("main"), Optional.empty(), 0, true,
                List.of(version()), "")));

        controller.next();

        assertEquals(7, requestedOffset.get());
        assertEquals(2, controller.pageNumber());
    }

    @Test
    void readsOnlyItsAssignedPageChannel() {
        HistorySnapshotPayload snapshot = snapshot();
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        var channel = new ClientHistoryPageStore.Channel(new UUID(0, 9));
        UUID request = new UUID(0, 10);
        WorkspaceHistoryController controller = new WorkspaceHistoryController(
                snapshot, pages, channel, (branch, zone, offset, limit) -> {
                    pages.begin(channel, request, snapshot.dimensionId(),
                            snapshot.workspaceId(), branch, zone, offset);
                    return request;
                });
        controller.ensurePageSize(7);

        assertTrue(pages.accept(new HistoryPagePayload(
                request, snapshot.dimensionId(), snapshot.workspaceId(),
                new BranchName("main"), Optional.empty(), 0, false,
                List.of(version()), "")));
        assertEquals(List.of(version()), controller.versions());
        assertTrue(pages.page(
                snapshot.dimensionId(), snapshot.workspaceId(),
                new BranchName("main"), Optional.empty()).isEmpty());
    }

    private static HistorySnapshotPayload snapshot() {
        return new HistorySnapshotPayload(
                "minecraft:overworld", id('1'), 0, 0, false, false,
                new UUID(0, 7), "Build", "main", List.of(),
                List.of(version()),
                List.of(new HistorySnapshotPayload.Branch("main", id('1'), true)),
                List.of(), List.of());
    }

    private static HistorySnapshotPayload.Version version() {
        return new HistorySnapshotPayload.Version(
                id('1'), "Save", "Builder", 1, CommitKind.MANUAL);
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
