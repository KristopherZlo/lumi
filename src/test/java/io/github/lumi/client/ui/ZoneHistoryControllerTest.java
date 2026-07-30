package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ZoneHistoryControllerTest {
    @Test
    void pagesAndCyclesBranchesWithoutSharingState() {
        UUID workspace = new UUID(0, 1);
        UUID zone = new UUID(0, 2);
        UUID request = new UUID(0, 3);
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        AtomicReference<BranchName> requestedBranch = new AtomicReference<>();
        HistorySnapshotPayload snapshot = snapshot(workspace);
        ZoneHistoryController controller = new ZoneHistoryController(
                snapshot, zone, pages,
                (branch, ignored, offset, limit, query) -> {
                    requestedBranch.set(branch);
                    pages.begin(request, snapshot.dimensionId(), workspace,
                            branch, Optional.of(zone), offset);
                    return request;
                });

        controller.request();
        assertEquals(new BranchName("main"), requestedBranch.get());
        assertTrue(pages.accept(new HistoryPagePayload(
                request, snapshot.dimensionId(), workspace,
                new BranchName("main"), Optional.of(zone), 0, true,
                List.of(version()), List.of(
                        new BranchName("main"), new BranchName("idea")), "")));
        assertTrue(controller.loadNextPage());
        assertEquals(2, controller.branches(snapshot.branches()).size());

        controller.nextBranch(snapshot.branches());
        assertEquals(new BranchName("idea"), requestedBranch.get());
        assertEquals(0, controller.offset());
    }

    @Test
    void appendsLoadedZonePagesForTheScrollView() {
        UUID workspace = new UUID(0, 21);
        UUID zone = new UUID(0, 22);
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        AtomicReference<UUID> request = new AtomicReference<>();
        ZoneHistoryController controller = new ZoneHistoryController(
                snapshot(workspace), zone, pages,
                (branch, ignored, offset, limit, query) -> {
                    UUID id = UUID.randomUUID();
                    request.set(id);
                    pages.begin(id, "minecraft:overworld", workspace,
                            branch, Optional.of(zone), offset);
                    return id;
                });
        controller.request();
        assertTrue(pages.accept(new HistoryPagePayload(
                request.get(), "minecraft:overworld", workspace,
                new BranchName("main"), Optional.of(zone), 0, true,
                List.of(version()), "")));
        assertEquals(1, controller.versions(List.of()).size());

        assertTrue(controller.loadNextPage());
        assertTrue(pages.accept(new HistoryPagePayload(
                request.get(), "minecraft:overworld", workspace,
                new BranchName("main"), Optional.of(zone),
                ZoneHistoryController.PAGE_SIZE, false,
                List.of(version('2')), "")));
        assertEquals(2, controller.versions(List.of()).size());
    }

    @Test
    void resetsPagingWhenTheServerSearchChanges() {
        UUID workspace = new UUID(0, 11);
        UUID zone = new UUID(0, 12);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> requestedQuery = new AtomicReference<>();
        ZoneHistoryController controller = new ZoneHistoryController(
                snapshot(workspace), zone, new ClientHistoryPageStore(),
                (branch, ignored, offset, limit, query) -> {
                    requests.incrementAndGet();
                    requestedQuery.set(query + ":" + offset);
                    return UUID.randomUUID();
                });

        controller.search("tow");
        controller.search("  tower  ");
        for (int tick = 1;
                tick < ZoneHistoryController.SEARCH_DEBOUNCE_TICKS;
                tick++) {
            controller.tick();
        }

        assertEquals(0, requests.get());
        controller.tick();
        assertEquals(1, requests.get());
        assertEquals("tower:0", requestedQuery.get());
    }

    private static HistorySnapshotPayload snapshot(UUID workspace) {
        return new HistorySnapshotPayload(
                "minecraft:overworld", id('1'), 0, 0, false, false,
                workspace, "Build", "main", List.of(),
                List.of(version()),
                List.of(
                        new HistorySnapshotPayload.Branch("main", id('1'), true),
                        new HistorySnapshotPayload.Branch("idea", id('1'), false)),
                List.of(), List.of());
    }

    private static HistorySnapshotPayload.Version version() {
        return version('1');
    }

    private static HistorySnapshotPayload.Version version(char digit) {
        return new HistorySnapshotPayload.Version(
                id(digit), "Save", "Builder", digit, CommitKind.ZONE);
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
