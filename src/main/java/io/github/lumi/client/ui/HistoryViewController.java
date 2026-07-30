package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns the shared Cards/Graph mode and branch-filtered immutable history view. */
final class HistoryViewController {
    enum Mode {
        CARDS,
        GRAPH
    }

    private final HistorySearchController search = new HistorySearchController();
    private final HistoryScope scope;
    private Mode mode = Mode.CARDS;
    private String branch = "";

    HistoryViewController(HistoryScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    HistoryScope scope() {
        return scope;
    }

    Mode mode() {
        return mode;
    }

    void show(Mode replacement) {
        mode = Objects.requireNonNull(replacement, "replacement");
    }

    static boolean shouldPrefetch(int scroll, int capacity, int loaded) {
        return scroll + capacity * 2 >= loaded;
    }

    String branch() {
        return branch;
    }

    void nextBranch(List<HistorySnapshotPayload.Branch> branches) {
        Objects.requireNonNull(branches, "branches");
        if (branches.isEmpty()) {
            branch = "";
            return;
        }
        int current = -1;
        for (int index = 0; index < branches.size(); index++) {
            if (branches.get(index).name().equals(branch)) {
                current = index;
                break;
            }
        }
        branch = current + 1 < branches.size()
                ? branches.get(current + 1).name() : "";
    }

    List<HistorySnapshotPayload.Version> visible(
            HistorySnapshotPayload snapshot, String query) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<HistorySnapshotPayload.Version> matching =
                filtered(snapshot.versions(), query);
        if (branch.isEmpty()) {
            return matching;
        }
        HistorySnapshotPayload.Branch selected = snapshot.branches().stream()
                .filter(candidate -> candidate.name().equals(branch))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            branch = "";
            return matching;
        }
        Set<CommitId> reachable = reachableFrom(
                selected.head(), snapshot.versions());
        return matching.stream()
                .filter(version -> reachable.contains(version.id()))
                .toList();
    }

    List<HistorySnapshotPayload.Version> filtered(
            List<HistorySnapshotPayload.Version> versions, String query) {
        return search.filter(
                Objects.requireNonNull(versions, "versions"), query);
    }

    private Set<CommitId> reachableFrom(
            CommitId head, List<HistorySnapshotPayload.Version> versions) {
        Map<CommitId, HistorySnapshotPayload.Version> byId = new HashMap<>();
        versions.forEach(version -> byId.put(version.id(), version));
        Set<CommitId> reachable = new HashSet<>();
        ArrayDeque<CommitId> pending = new ArrayDeque<>();
        pending.add(head);
        while (!pending.isEmpty()) {
            CommitId id = pending.removeFirst();
            HistorySnapshotPayload.Version version = byId.get(id);
            if (version != null && reachable.add(id)) {
                pending.addAll(version.parents());
            }
        }
        return reachable;
    }
}
