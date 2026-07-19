package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns branch and page state for one zone's bounded history query. */
final class ZoneHistoryController {
    static final int PAGE_SIZE = 3;
    private final HistorySnapshotPayload snapshot;
    private final UUID zoneId;
    private final ClientHistoryPageStore pages;
    private final Requester requester;
    private BranchName branch;
    private int offset;

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
    }

    void request() {
        requester.request(branch, Optional.of(zoneId), offset, PAGE_SIZE);
    }

    Optional<HistoryPagePayload> page() {
        return pages.page(
                snapshot.dimensionId(), snapshot.workspaceId(),
                branch, Optional.of(zoneId));
    }

    List<HistorySnapshotPayload.Version> versions(
            List<HistorySnapshotPayload.Version> initial) {
        return page().map(HistoryPagePayload::versions)
                .orElseGet(() -> offset == 0
                        && branch.value().equals(snapshot.branchName())
                        ? List.copyOf(initial) : List.of());
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
        branch = new BranchName(branches.get(
                (current + 1) % branches.size()).name());
        offset = 0;
        request();
    }

    String error() {
        return page().map(HistoryPagePayload::error).orElse("");
    }

    @FunctionalInterface
    interface Requester {
        UUID request(
                BranchName branch,
                Optional<UUID> zoneId,
                int offset,
                int limit);
    }
}
