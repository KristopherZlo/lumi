package io.github.luma.ui.graph;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes a stable branch-aware lane layout for the workspace commit graph.
 *
 * <p>The graph is a single-parent DAG. Lanes are assigned from variant heads in
 * deterministic order, branch heads already on an existing path reuse that
 * lane, shared ancestors keep the first lane that reaches them, and each lane
 * remains visually active only between rows where a version is assigned to it.
 */
public final class CommitGraphLayout {

    private CommitGraphLayout() {
    }

    public static List<CommitGraphNode> build(
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            String activeVariantId
    ) {
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }

        List<ProjectVersion> orderedVersions = versions.stream()
                .sorted(Comparator.comparing(ProjectVersion::createdAt).reversed())
                .toList();
        if (orderedVersions.isEmpty()) {
            return List.of();
        }
        Map<String, ProjectVersion> versionMap = new HashMap<>();
        Map<String, Integer> rowIndexByVersionId = new HashMap<>();
        for (int index = 0; index < orderedVersions.size(); index++) {
            ProjectVersion version = orderedVersions.get(index);
            versionMap.put(version.id(), version);
            rowIndexByVersionId.put(version.id(), index);
        }

        List<ProjectVariant> orderedVariants = variants == null
                ? List.of()
                : variants.stream()
                        .sorted(Comparator
                                .comparing((ProjectVariant variant) -> !variant.id().equals(activeVariantId))
                                .thenComparing(ProjectVariant::createdAt)
                                .thenComparing(ProjectVariant::id))
                        .toList();

        Map<String, Integer> versionLane = new LinkedHashMap<>();
        Map<String, List<String>> headVariantsByVersion = new LinkedHashMap<>();
        int nextLane = 0;

        for (ProjectVariant variant : orderedVariants) {
            if (variant.headVersionId() == null || variant.headVersionId().isBlank()) {
                continue;
            }
            headVariantsByVersion.computeIfAbsent(variant.headVersionId(), ignored -> new ArrayList<>()).add(variant.id());
            if (versionLane.containsKey(variant.headVersionId())) {
                continue;
            }

            int lane = nextLane++;
            ProjectVersion cursor = versionMap.get(variant.headVersionId());
            while (cursor != null) {
                versionLane.putIfAbsent(cursor.id(), lane);
                if (cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()) {
                    break;
                }
                cursor = versionMap.get(cursor.parentVersionId());
            }
        }

        for (ProjectVersion version : orderedVersions) {
            if (versionLane.containsKey(version.id())) {
                continue;
            }
            int lane = nextLane++;
            versionLane.put(version.id(), lane);
        }

        Map<Integer, LaneSpan> laneSpans = new LinkedHashMap<>();
        for (ProjectVersion version : orderedVersions) {
            Integer rowIndex = rowIndexByVersionId.get(version.id());
            Integer lane = versionLane.get(version.id());
            if (rowIndex == null || lane == null) {
                continue;
            }
            LaneSpan existing = laneSpans.get(lane);
            laneSpans.put(lane, existing == null ? new LaneSpan(rowIndex, rowIndex) : existing.include(rowIndex));
        }

        int laneCount = Math.max(1, nextLane);
        List<CommitGraphNode> nodes = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < orderedVersions.size(); rowIndex++) {
            ProjectVersion version = orderedVersions.get(rowIndex);
            List<Integer> activeLanes = new ArrayList<>();
            for (Map.Entry<Integer, LaneSpan> entry : laneSpans.entrySet()) {
                if (entry.getValue().contains(rowIndex)) {
                    activeLanes.add(entry.getKey());
                }
            }
            String parentVersionId = version.parentVersionId();
            int parentLane = -1;
            int parentRowIndex = -1;
            if (parentVersionId != null && !parentVersionId.isBlank()) {
                parentLane = versionLane.getOrDefault(parentVersionId, -1);
                parentRowIndex = rowIndexByVersionId.getOrDefault(parentVersionId, -1);
            }
            nodes.add(new CommitGraphNode(
                    version,
                    rowIndex,
                    versionLane.getOrDefault(version.id(), 0),
                    parentLane,
                    parentRowIndex,
                    laneCount,
                    List.copyOf(activeLanes),
                    List.copyOf(headVariantsByVersion.getOrDefault(version.id(), List.of())),
                    headVariantsByVersion.getOrDefault(version.id(), List.of()).contains(activeVariantId)
            ));
        }

        return List.copyOf(nodes);
    }

    private record LaneSpan(int minRow, int maxRow) {

        private LaneSpan include(int rowIndex) {
            return new LaneSpan(Math.min(this.minRow, rowIndex), Math.max(this.maxRow, rowIndex));
        }

        private boolean contains(int rowIndex) {
            return rowIndex >= this.minRow && rowIndex <= this.maxRow;
        }
    }
}
