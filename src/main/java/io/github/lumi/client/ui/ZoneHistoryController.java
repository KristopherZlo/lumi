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
    static final int SEARCH_DEBOUNCE_TICKS = 4;
    private final HistorySnapshotPayload snapshot;
    private final UUID zoneId;
    private final ClientHistoryPageStore pages;
    private final Requester requester;
    private BranchName branch;
    private int offset;
    private String query = "";
    private final List<HistorySnapshotPayload.Version> loaded = new ArrayList<>();
    private List<HistorySnapshotPayload.Version> loadedView = List.of();
    private List<HistorySnapshotPayload.Version> initialView = List.of();
    private List<HistorySnapshotPayload.Version> initialSource = List.of();
    private List<BranchName> availableBranches = List.of();
    private UUID loadedRequest;
    private long observedRevision;
    private long observedMetadataRevision;
    private boolean awaitingRefresh;
    private int searchDelay;

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
        observedMetadataRevision = pages.metadataRevision();
    }

    void request() {
        searchDelay = 0;
        requester.request(
                branch, Optional.of(zoneId), offset, PAGE_SIZE, query);
    }

    Optional<HistoryPagePayload> page() {
        synchronizeInvalidation();
        if (searchDelay > 0) return Optional.empty();
        return pages.page(
                snapshot.dimensionId(), snapshot.workspaceId(),
                branch, Optional.of(zoneId));
    }

    List<HistorySnapshotPayload.Version> versions(
            List<HistorySnapshotPayload.Version> initial) {
        acceptPage();
        synchronizeMetadata(initial);
        if (!loaded.isEmpty()) {
            return loadedView;
        }
        if (awaitingRefresh) return List.of();
        return offset == 0 && branch.value().equals(snapshot.branchName())
                ? initialView : List.of();
    }

    List<HistorySnapshotPayload.Branch> branches(
            List<HistorySnapshotPayload.Branch> workspaceBranches) {
        acceptPage();
        return workspaceBranches.stream()
                .filter(candidate -> availableBranches.stream().anyMatch(
                        name -> name.value().equals(candidate.name())))
                .toList();
    }

    private void acceptPage() {
        page().filter(current -> !current.requestId().equals(loadedRequest))
                .ifPresent(current -> {
                    if (current.offset() == 0) {
                        loaded.clear();
                        availableBranches = current.branches();
                    }
                    loaded.addAll(current.versions());
                    loadedView = pages.versions(snapshot.dimensionId(), loaded);
                    loadedRequest = current.requestId();
                    awaitingRefresh = false;
                });
    }

    BranchName branch() {
        return branch;
    }

    int offset() {
        return offset;
    }

    boolean loadNextPage() {
        Optional<HistoryPagePayload> current = page();
        if (current.isEmpty() || current.orElseThrow().offset() != offset) {
            return false;
        }
        acceptPage();
        if (!current.orElseThrow().hasMore()) return false;
        offset += PAGE_SIZE;
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
        awaitingRefresh = true;
        searchDelay = SEARCH_DEBOUNCE_TICKS;
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
        loadedView = List.of();
        loadedRequest = null;
    }

    private void synchronizeMetadata(
            List<HistorySnapshotPayload.Version> initial) {
        long current = pages.metadataRevision();
        if (initial == initialSource && current == observedMetadataRevision) {
            return;
        }
        observedMetadataRevision = current;
        initialSource = initial;
        initialView = pages.versions(snapshot.dimensionId(), initial);
        loadedView = pages.versions(snapshot.dimensionId(), loaded);
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
