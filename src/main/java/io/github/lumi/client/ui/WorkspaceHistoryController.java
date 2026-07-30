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

/** Owns branch-filtered, incrementally loaded history for the scroll view. */
final class WorkspaceHistoryController {
    static final int SEARCH_DEBOUNCE_TICKS = 4;
    private final HistorySnapshotPayload snapshot;
    private final ClientHistoryPageStore pages;
    private final ClientHistoryPageStore.Channel channel;
    private final ZoneHistoryController.Requester requester;
    private BranchName branch;
    private int offset;
    private int pageSize;
    private String query = "";
    private final List<HistorySnapshotPayload.Version> loaded = new ArrayList<>();
    private UUID loadedRequest;
    private long observedRevision;
    private boolean awaitingRefresh;
    private int searchDelay;

    WorkspaceHistoryController(
            HistorySnapshotPayload snapshot,
            ClientHistoryPageStore pages,
            ZoneHistoryController.Requester requester) {
        this(snapshot, pages, null, requester);
    }

    WorkspaceHistoryController(
            HistorySnapshotPayload snapshot,
            ClientHistoryPageStore pages,
            ClientHistoryPageStore.Channel channel,
            ZoneHistoryController.Requester requester) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.channel = channel;
        this.requester = Objects.requireNonNull(requester, "requester");
        branch = new BranchName(snapshot.branchName());
        observedRevision = pages.revision(snapshot.dimensionId());
    }

    boolean matches(HistorySnapshotPayload candidate) {
        return snapshot.dimensionId().equals(candidate.dimensionId())
                && snapshot.workspaceId().equals(candidate.workspaceId())
                && snapshot.revision() == candidate.revision();
    }

    void ensurePageSize(int replacement) {
        if (replacement < 1 || replacement > 64) {
            throw new IllegalArgumentException("Invalid history page size");
        }
        if (pageSize != replacement) {
            pageSize = replacement;
            offset = 0;
            request();
        }
    }

    Optional<HistoryPagePayload> page() {
        synchronizeInvalidation();
        if (searchDelay > 0) return Optional.empty();
        if (channel != null) {
            return pages.page(
                    channel, snapshot.dimensionId(), snapshot.workspaceId(),
                    branch, Optional.empty());
        }
        return pages.page(
                snapshot.dimensionId(), snapshot.workspaceId(),
                branch, Optional.empty());
    }

    List<HistorySnapshotPayload.Version> versions() {
        Optional<HistoryPagePayload> currentPage = page();
        currentPage.filter(current -> !current.requestId().equals(loadedRequest))
                .ifPresent(current -> {
                    if (current.offset() == 0) loaded.clear();
                    loaded.addAll(current.versions());
                    loadedRequest = current.requestId();
                    awaitingRefresh = false;
                });
        if (!loaded.isEmpty()) {
            return pages.versions(snapshot.dimensionId(), loaded);
        }
        if (awaitingRefresh) return List.of();
        return offset == 0 && branch.value().equals(snapshot.branchName())
                ? pages.versions(snapshot.dimensionId(), snapshot.versions())
                : List.of();
    }

    BranchName branch() {
        return branch;
    }

    int pageNumber() {
        return pageSize == 0 ? 1 : offset / pageSize + 1;
    }

    boolean hasPrevious() {
        return offset > 0;
    }

    boolean hasNext() {
        return page().map(HistoryPagePayload::hasMore).orElse(false);
    }

    void previous() {
        if (hasPrevious()) {
            offset = Math.max(0, offset - pageSize);
            request();
        }
    }

    void next() {
        if (hasNext()) {
            offset += pageSize;
            request();
        }
    }

    boolean loadNextPage() {
        Optional<HistoryPagePayload> current = page();
        if (current.isEmpty() || current.orElseThrow().offset() != offset) {
            return false;
        }
        versions();
        if (!current.orElseThrow().hasMore()) {
            return false;
        }
        offset += pageSize;
        request();
        return true;
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
        if (pageSize > 0) request();
    }

    void search(String replacement) {
        String normalized = Objects.requireNonNull(replacement, "replacement")
                .trim();
        if (query.equals(normalized)) {
            return;
        }
        query = normalized;
        reset();
        awaitingRefresh = true;
        if (pageSize > 0) {
            searchDelay = SEARCH_DEBOUNCE_TICKS;
        }
    }

    void tick() {
        if (searchDelay > 0 && --searchDelay == 0) request();
    }

    String error() {
        return page().map(HistoryPagePayload::error).orElse("");
    }

    private void reset() {
        offset = 0;
        loaded.clear();
        loadedRequest = null;
    }

    private void request() {
        searchDelay = 0;
        requester.request(
                branch, Optional.empty(), offset, pageSize, query);
    }

    private void synchronizeInvalidation() {
        long current = pages.revision(snapshot.dimensionId());
        if (current == observedRevision) return;
        observedRevision = current;
        reset();
        awaitingRefresh = true;
        if (pageSize > 0) request();
    }
}
