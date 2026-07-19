package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Computes deterministic branch lanes and parent edges for the history graph. */
final class HistoryGraphLayout {
    List<Node> build(
            List<HistorySnapshotPayload.Version> versions,
            List<HistorySnapshotPayload.Branch> branches) {
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(branches, "branches");
        List<HistorySnapshotPayload.Version> ordered = versions.stream()
                .sorted(Comparator
                        .comparingLong(HistorySnapshotPayload.Version::timestampMillis)
                        .reversed()
                        .thenComparing(version -> version.id().hex()))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        Map<CommitId, HistorySnapshotPayload.Version> byId = new HashMap<>();
        Map<CommitId, Integer> rows = new HashMap<>();
        for (int row = 0; row < ordered.size(); row++) {
            byId.put(ordered.get(row).id(), ordered.get(row));
            rows.put(ordered.get(row).id(), row);
        }

        Map<CommitId, Integer> lanes = new LinkedHashMap<>();
        Map<CommitId, List<String>> heads = new HashMap<>();
        int nextLane = 0;
        List<HistorySnapshotPayload.Branch> orderedBranches = branches.stream()
                .sorted(Comparator
                        .comparing(HistorySnapshotPayload.Branch::active).reversed()
                        .thenComparing(HistorySnapshotPayload.Branch::name))
                .toList();
        for (HistorySnapshotPayload.Branch branch : orderedBranches) {
            if (!byId.containsKey(branch.head())) {
                continue;
            }
            heads.computeIfAbsent(branch.head(), ignored -> new ArrayList<>())
                    .add(branch.name());
            if (lanes.containsKey(branch.head())) {
                continue;
            }
            int lane = nextLane++;
            HistorySnapshotPayload.Version cursor = byId.get(branch.head());
            while (cursor != null && lanes.putIfAbsent(cursor.id(), lane) == null) {
                cursor = cursor.parents().isEmpty()
                        ? null : byId.get(cursor.parents().getFirst());
            }
        }

        for (int index = ordered.size() - 1; index >= 0; index--) {
            HistorySnapshotPayload.Version version = ordered.get(index);
            if (lanes.containsKey(version.id())) {
                continue;
            }
            Integer parentLane = version.parents().stream()
                    .map(lanes::get)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            lanes.put(version.id(), parentLane == null ? nextLane++ : parentLane);
        }

        int laneCount = Math.max(1, nextLane);
        List<Node> nodes = new ArrayList<>(ordered.size());
        for (int row = 0; row < ordered.size(); row++) {
            HistorySnapshotPayload.Version version = ordered.get(row);
            int childRow = row;
            List<Edge> edges = version.parents().stream()
                    .filter(rows::containsKey)
                    .map(parent -> new Edge(
                            lanes.get(version.id()), childRow,
                            lanes.get(parent), rows.get(parent)))
                    .toList();
            List<String> branchHeads = List.copyOf(
                    heads.getOrDefault(version.id(), List.of()));
            boolean activeHead = orderedBranches.stream()
                    .anyMatch(branch -> branch.active()
                            && branch.head().equals(version.id()));
            nodes.add(new Node(
                    version, row, lanes.get(version.id()), laneCount,
                    edges, branchHeads, activeHead));
        }
        return List.copyOf(nodes);
    }

    record Node(
            HistorySnapshotPayload.Version version,
            int row,
            int lane,
            int laneCount,
            List<Edge> parentEdges,
            List<String> branchHeads,
            boolean activeHead) {
        Node {
            parentEdges = List.copyOf(parentEdges);
            branchHeads = List.copyOf(branchHeads);
        }
    }

    record Edge(int childLane, int childRow, int parentLane, int parentRow) {
    }
}
