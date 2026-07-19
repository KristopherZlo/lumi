package io.github.lumi.client.ui;

import io.github.lumi.client.ClientPackageAccess;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.storage.packageformat.LumiPackageDirectory;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Small mutable view state for the bounded package browser. */
final class PackageBrowserState {
    private final Optional<ClientPackageAccess> localAccess;
    private final List<HistorySnapshotPayload.Branch> importedBranches;
    private List<LumiPackageDirectory.Entry> localPackages = List.of();
    private boolean showImported;
    private int scroll;
    private HistorySnapshotPayload.Branch pendingDelete;

    PackageBrowserState(
            Optional<ClientPackageAccess> localAccess,
            List<HistorySnapshotPayload.Branch> branches) {
        this.localAccess = Objects.requireNonNull(localAccess, "localAccess");
        importedBranches = Objects.requireNonNull(branches, "branches").stream()
                .filter(branch -> branch.name().startsWith("import/")
                        || branch.name().contains("/import/"))
                .toList();
    }

    void refreshLocal() throws IOException {
        localPackages = localAccess.isPresent()
                ? localAccess.orElseThrow().list() : List.of();
    }

    boolean canOpenFolder() {
        return localAccess.isPresent();
    }

    void openFolder() throws IOException {
        localAccess.orElseThrow().openFolder();
    }

    boolean showImported() {
        return showImported;
    }

    void selectTab(boolean imported) {
        showImported = imported;
        scroll = 0;
        pendingDelete = null;
    }

    int size() {
        return showImported ? importedBranches.size() : localPackages.size();
    }

    int start(int rows) {
        return Math.min(scroll, Math.max(0, size() - rows));
    }

    int end(int rows) {
        return Math.min(start(rows) + rows, size());
    }

    LumiPackageDirectory.Entry local(int index) {
        return localPackages.get(index);
    }

    HistorySnapshotPayload.Branch imported(int index) {
        return importedBranches.get(index);
    }

    void scroll(int delta, int rows) {
        int maximum = Math.max(0, size() - rows);
        scroll = Math.max(0, Math.min(maximum, scroll + delta));
    }

    Optional<HistorySnapshotPayload.Branch> pendingDelete() {
        return Optional.ofNullable(pendingDelete);
    }

    void confirmDelete(HistorySnapshotPayload.Branch branch) {
        pendingDelete = Objects.requireNonNull(branch, "branch");
    }

    void cancelDelete() {
        pendingDelete = null;
    }
}
