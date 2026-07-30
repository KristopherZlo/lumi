package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkspaceHistoryControllerTest {
    @Test
    void advancesByTheActualVisiblePageSize() {
        HistorySnapshotPayload snapshot = snapshot();
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        AtomicInteger requestedOffset = new AtomicInteger();
        UUID request = new UUID(0, 8);
        WorkspaceHistoryController controller = new WorkspaceHistoryController(
                snapshot, pages, (branch, zone, offset, limit, query) -> {
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

        assertTrue(controller.loadNextPage());

        assertEquals(7, requestedOffset.get());
    }

    @Test
    void readsOnlyItsAssignedPageChannel() {
        HistorySnapshotPayload snapshot = snapshot();
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        var channel = new ClientHistoryPageStore.Channel(new UUID(0, 9));
        UUID request = new UUID(0, 10);
        WorkspaceHistoryController controller = new WorkspaceHistoryController(
                snapshot, pages, channel,
                (branch, zone, offset, limit, query) -> {
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

    @Test
    void requestsTheFirstFilteredPageFromTheServer() {
        HistorySnapshotPayload snapshot = snapshot();
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        AtomicInteger requestedOffset = new AtomicInteger(-1);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<UUID> request = new AtomicReference<>();
        AtomicReference<String> requestedQuery = new AtomicReference<>();
        WorkspaceHistoryController controller = new WorkspaceHistoryController(
                snapshot, pages,
                (branch, zone, offset, limit, query) -> {
                    requests.incrementAndGet();
                    requestedOffset.set(offset);
                    requestedQuery.set(query);
                    UUID id = UUID.randomUUID();
                    request.set(id);
                    pages.begin(id, snapshot.dimensionId(),
                            snapshot.workspaceId(), branch, zone, offset);
                    return id;
                });
        controller.ensurePageSize(7);
        assertTrue(pages.accept(new HistoryPagePayload(
                request.get(), snapshot.dimensionId(), snapshot.workspaceId(),
                new BranchName("main"), Optional.empty(), 0, false,
                List.of(version()), "")));
        assertEquals(1, controller.versions().size());

        controller.search("tow");
        controller.search("  tower  ");
        for (int tick = 1;
                tick < WorkspaceHistoryController.SEARCH_DEBOUNCE_TICKS;
                tick++) {
            controller.tick();
        }

        assertEquals(1, requests.get());
        controller.tick();
        assertEquals(2, requests.get());
        assertEquals(0, requestedOffset.get());
        assertEquals("tower", requestedQuery.get());
        assertTrue(controller.versions().isEmpty());
    }

    @Test
    void appendsServerPagesAndResetsWhenATabSelectsAnotherBranch() {
        HistorySnapshotPayload snapshot = snapshot();
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        AtomicInteger sequence = new AtomicInteger();
        AtomicReference<UUID> request = new AtomicReference<>();
        WorkspaceHistoryController controller = new WorkspaceHistoryController(
                snapshot, pages, (branch, zone, offset, limit, query) -> {
                    UUID id = new UUID(0, sequence.incrementAndGet());
                    request.set(id);
                    pages.begin(id, snapshot.dimensionId(),
                            snapshot.workspaceId(), branch, zone, offset);
                    return id;
                });
        controller.ensurePageSize(2);
        assertTrue(pages.accept(new HistoryPagePayload(
                request.get(), snapshot.dimensionId(), snapshot.workspaceId(),
                new BranchName("main"), Optional.empty(), 0, true,
                List.of(version('1'), version('2')), "")));
        assertEquals(2, controller.versions().size());

        assertTrue(controller.loadNextPage());
        assertTrue(pages.accept(new HistoryPagePayload(
                request.get(), snapshot.dimensionId(), snapshot.workspaceId(),
                new BranchName("main"), Optional.empty(), 2, false,
                List.of(version('3')), "")));
        assertEquals(3, controller.versions().size());

        controller.selectBranch("idea");
        assertTrue(controller.versions().isEmpty());
        assertEquals("idea", controller.branch().value());
    }

    @Test
    void loadsEachReadyPageOnceForContinuousPresentations() {
        HistorySnapshotPayload snapshot = snapshot();
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        AtomicInteger sequence = new AtomicInteger();
        AtomicReference<UUID> request = new AtomicReference<>();
        WorkspaceHistoryController controller = new WorkspaceHistoryController(
                snapshot, pages, (branch, zone, offset, limit, query) -> {
                    UUID id = new UUID(0, sequence.incrementAndGet());
                    request.set(id);
                    pages.begin(id, snapshot.dimensionId(),
                            snapshot.workspaceId(), branch, zone, offset);
                    return id;
                });
        controller.ensurePageSize(2);
        assertTrue(pages.accept(new HistoryPagePayload(
                request.get(), snapshot.dimensionId(), snapshot.workspaceId(),
                new BranchName("main"), Optional.empty(), 0, true,
                List.of(version('1'), version('2')), "")));

        assertTrue(controller.loadNextPage());
        assertFalse(controller.loadNextPage());
        assertTrue(pages.accept(new HistoryPagePayload(
                request.get(), snapshot.dimensionId(), snapshot.workspaceId(),
                new BranchName("main"), Optional.empty(), 2, false,
                List.of(version('3')), "")));
        assertFalse(controller.loadNextPage());
        List<HistorySnapshotPayload.Version> versions = controller.versions();
        assertEquals(3, versions.size());
        assertSame(versions, controller.versions());
    }

    @Test
    void appliesMetadataEditsToAnAlreadyLoadedVersion() {
        HistorySnapshotPayload snapshot = snapshot();
        ClientHistoryPageStore pages = new ClientHistoryPageStore();
        UUID request = new UUID(0, 11);
        WorkspaceHistoryController controller = new WorkspaceHistoryController(
                snapshot, pages, (branch, zone, offset, limit, query) -> {
                    pages.begin(request, snapshot.dimensionId(),
                            snapshot.workspaceId(), branch, zone, offset);
                    return request;
                });
        controller.ensurePageSize(7);
        assertTrue(pages.accept(new HistoryPagePayload(
                request, snapshot.dimensionId(), snapshot.workspaceId(),
                new BranchName("main"), Optional.empty(), 0, false,
                List.of(version()), "")));
        assertEquals("Save 1", controller.versions().getFirst().message());

        pages.replaceVersionName(
                snapshot.dimensionId(), version().id(), "Castle");
        pages.replaceVersionTags(
                snapshot.dimensionId(), version().id(),
                new VersionTags(List.of("finished")));

        HistorySnapshotPayload.Version updated =
                controller.versions().getFirst();
        assertEquals("Castle", updated.message());
        assertEquals(List.of("finished"), updated.tags().values());
        assertSame(updated, controller.versions().getFirst());
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
        return version('1');
    }

    private static HistorySnapshotPayload.Version version(char digit) {
        return new HistorySnapshotPayload.Version(
                id(digit), "Save " + digit, "Builder", digit, CommitKind.MANUAL);
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
