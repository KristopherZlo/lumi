package io.github.luma.ui.graph;

import io.github.luma.domain.model.ProjectVersion;
import java.util.List;

/**
 * One rendered node in the workspace commit graph.
 */
public record CommitGraphNode(
        ProjectVersion version,
        int lane,
        int laneCount,
        List<Integer> activeLanes,
        List<String> headVariants,
        boolean activeHead
) {
}
