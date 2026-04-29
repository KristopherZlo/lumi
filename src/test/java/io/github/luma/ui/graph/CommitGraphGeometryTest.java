package io.github.luma.ui.graph;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitGraphGeometryTest {

    @Test
    void hitTestingReturnsVersionRowFromLocalCoordinates() {
        Instant baseTime = Instant.parse("2026-04-21T00:00:00Z");
        List<ProjectVersion> versions = List.of(
                this.version("v0001", "main", "", baseTime),
                this.version("v0002", "main", "v0001", baseTime.plusSeconds(1))
        );
        List<ProjectVariant> variants = List.of(new ProjectVariant("main", "main", "v0001", "v0002", true, baseTime));
        List<CommitGraphNode> nodes = CommitGraphLayout.build(versions, variants, "main");
        CommitGraphGeometry geometry = new CommitGraphGeometry(0, 0, 320, 1, true, nodes);

        assertEquals(
                "v0002",
                geometry.nodeAtLocal(24, CommitGraphGeometry.TOP_PADDING + CommitGraphGeometry.LEGEND_HEIGHT + 4)
                        .orElseThrow()
                        .version()
                        .id()
        );
        assertTrue(geometry.nodeAtLocal(24, CommitGraphGeometry.TOP_PADDING - 1).isEmpty());
    }

    @Test
    void parentConnectorUsesOrthogonalSegments() {
        Instant baseTime = Instant.parse("2026-04-21T00:00:00Z");
        List<ProjectVersion> versions = List.of(
                this.version("v0001", "main", "", baseTime),
                this.version("v0002", "main", "v0001", baseTime.plusSeconds(1)),
                this.version("v0003", "branch-a", "v0001", baseTime.plusSeconds(2))
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, baseTime),
                new ProjectVariant("branch-a", "Branch A", "v0001", "v0003", false, baseTime.plusSeconds(1))
        );
        List<CommitGraphNode> nodes = CommitGraphLayout.build(versions, variants, "main");
        CommitGraphNode branchNode = nodes.stream()
                .filter(node -> node.version().id().equals("v0003"))
                .findFirst()
                .orElseThrow();
        CommitGraphGeometry geometry = new CommitGraphGeometry(0, 0, 320, branchNode.laneCount(), true, nodes);

        List<CommitGraphGeometry.ConnectorSegment> segments = geometry.parentConnectorSegments(branchNode);

        assertEquals(3, segments.size());
        assertTrue(segments.stream().allMatch(CommitGraphGeometry.ConnectorSegment::orthogonal));
        assertFalse(segments.stream().anyMatch(segment -> segment.x1() != segment.x2() && segment.y1() != segment.y2()));
    }

    private ProjectVersion version(String id, String variantId, String parentId, Instant createdAt) {
        return new ProjectVersion(
                id,
                "project",
                variantId,
                parentId == null ? "" : parentId,
                "",
                List.of("patch-" + id),
                VersionKind.MANUAL,
                "Zlo",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                createdAt
        );
    }
}
