package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Owns branch and bounded page state for the workspace history screen. */
final class WorkspaceHistoryController {
    private final HistorySnapshotPayload snapshot;
    private final ClientHistoryPageStore pages;
    private final ClientHistoryPageStore.Channel channel;
    private final ZoneHistoryController.Requester requester;
    private BranchName branch;
    private int offset;
    private int pageSize;

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
        return page().map(HistoryPagePayload::versions)
                .orElseGet(() -> offset == 0
                        && branch.value().equals(snapshot.branchName())
                        ? snapshot.versions() : List.of());
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
        branch = new BranchName(branches.get(
                (current + 1) % branches.size()).name());
        offset = 0;
        if (pageSize > 0) {
            request();
        }
    }

    String error() {
        return page().map(HistoryPagePayload::error).orElse("");
    }

    private void request() {
        requester.request(
                branch, Optional.empty(), offset, pageSize);
    }
}
