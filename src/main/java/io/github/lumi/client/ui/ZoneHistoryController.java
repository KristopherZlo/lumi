package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns branch-filtered, incrementally loaded history for one zone. */
public final class ZoneHistoryController {
    static final int PAGE_SIZE = HistoryPagePayload.MAX_VERSIONS;
    private final HistorySnapshotPayload snapshot;
    private final UUID zoneId;
    private final ClientHistoryPageStore pages;
    private final Requester requester;
    private BranchName branch;
    private int offset;
    private String query = "";
    private final List<HistorySnapshotPayload.Version> loaded = new ArrayList<>();
    private UUID loadedRequest;
    private long observedRevision;
    private boolean awaitingRefresh;

    ZoneHistoryController(
            HistorySnapshotPayload snapshot,
            UUID zoneId,
            ClientHistoryPageStore pages,
            Requester requester) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.requester = Objects.requireNonNull(requester, "requester");
        branch = new BranchName(snapshot.branchName());
        observedRevision = pages.revision(snapshot.dimensionId());
    }

    void request() {
        requester.request(
                branch, Optional.of(zoneId), offset, PAGE_SIZE, query);
    }

    Optional<HistoryPagePayload> page() {
        synchronizeInvalidation();
        return pages.page(
                snapshot.dimensionId(), snapshot.workspaceId(),
                branch, Optional.of(zoneId));
    }

    List<HistorySnapshotPayload.Version> versions(
            List<HistorySnapshotPayload.Version> initial) {
        Optional<HistoryPagePayload> currentPage = page();
        currentPage.filter(current -> !current.requestId().equals(loadedRequest))
                .ifPresent(current -> {
                    if (current.offset() == 0) loaded.clear();
                    loaded.addAll(current.versions());
                    loadedRequest = current.requestId();
                    awaitingRefresh = false;
                });
        if (!loaded.isEmpty()) return List.copyOf(loaded);
        if (awaitingRefresh) return List.of();
        return offset == 0 && branch.value().equals(snapshot.branchName())
                ? List.copyOf(initial) : List.of();
    }

    BranchName branch() {
        return branch;
    }

    int offset() {
        return offset;
    }

    boolean hasPrevious() {
        return offset > 0;
    }

    boolean hasNext() {
        return page().map(HistoryPagePayload::hasMore).orElse(false);
    }

    void previous() {
        if (hasPrevious()) {
            offset = Math.max(0, offset - PAGE_SIZE);
            request();
        }
    }

    void next() {
        if (hasNext()) {
            offset += PAGE_SIZE;
            request();
        }
    }

    void nextBranch(List<HistorySnapshotPayload.Branch> branches) {
        if (branches.isEmpty()) {
            return;
        }
        int current = -1;
        for (int index = 0; index < branches.size(); index++) {
            if (branches.get(index).name().equals(branch.value())) {
                current = index;
                break;
            }
        }
        selectBranch(branches.get((current + 1) % branches.size()).name());
    }

    void selectBranch(String replacement) {
        BranchName selected = new BranchName(replacement);
        if (branch.equals(selected)) return;
        branch = selected;
        reset();
        request();
    }

    void search(String replacement) {
        String normalized = Objects.requireNonNull(replacement, "replacement")
                .trim();
        if (query.equals(normalized)) {
            return;
        }
        query = normalized;
        reset();
        request();
    }

    String error() {
        return page().map(HistoryPagePayload::error).orElse("");
    }

    private void reset() {
        offset = 0;
        loaded.clear();
        loadedRequest = null;
    }

    private void synchronizeInvalidation() {
        long current = pages.revision(snapshot.dimensionId());
        if (current == observedRevision) return;
        observedRevision = current;
        reset();
        awaitingRefresh = true;
        request();
    }

    @FunctionalInterface
    public interface Requester {
        UUID request(
                BranchName branch,
                Optional<UUID> zoneId,
                int offset,
                int limit,
                String query);
    }
}
