package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryGraphLayoutTest {
    private final HistoryGraphLayout layout = new HistoryGraphLayout();

    @Test
    void activeBranchOwnsFirstLaneAndSharedAncestorsReuseIt() {
        var root = version('a', 1, List.of(), Optional.empty());
        var main = version('b', 2, List.of(root.id()), Optional.empty());
        var idea = version('c', 3, List.of(root.id()), Optional.empty());
        var nodes = layout.build(
                List.of(idea, main, root),
                List.of(branch("idea", idea.id(), false),
                        branch("workspace/id/main", main.id(), true)));

        assertEquals(main.id(), nodes.get(1).version().id());
        assertEquals(0, nodes.get(1).lane());
        assertEquals(0, nodes.get(2).lane());
        assertEquals(1, nodes.get(0).lane());
        assertEquals(List.of("main"), nodes.get(1).branchHeads());
        assertTrue(nodes.get(1).activeHead());
    }

    @Test
    void keepsBothMergeParentsAndZoneMarker() {
        var root = version('a', 1, List.of(), Optional.empty());
        var left = version('b', 2, List.of(root.id()), Optional.empty());
        var right = version('c', 3, List.of(root.id()), Optional.empty());
        UUID zone = UUID.randomUUID();
        var merge = version(
                'd', 4, List.of(left.id(), right.id()), Optional.of(zone));
        var nodes = layout.build(
                List.of(root, merge, right, left),
                List.of(branch("main", merge.id(), true)));

        assertEquals(merge.id(), nodes.getFirst().version().id());
        assertEquals(2, nodes.getFirst().parentEdges().size());
        assertEquals(Optional.of(zone), nodes.getFirst().version().zoneId());
        assertEquals(4, nodes.stream().map(HistoryGraphLayout.Node::row)
                .distinct().count());
    }

    @Test
    void automaticSiblingsShareOneVisualChainAndKeepTheirLaneWhenClipped() {
        var root = version('a', 1, List.of(), Optional.empty());
        var first = version('b', 2, List.of(root.id()), Optional.empty(),
                CommitKind.AUTO);
        var second = version('c', 3, List.of(root.id()), Optional.empty(),
                CommitKind.AUTO);
        var third = version('d', 4, List.of(root.id()), Optional.empty(),
                CommitKind.AUTO);
        var nodes = layout.build(
                List.of(root, first, third, second),
                List.of(branch("main", root.id(), true)));

        assertEquals(List.of(0, 0, 0, 0),
                nodes.stream().map(HistoryGraphLayout.Node::lane).toList());
        assertEquals(1, nodes.get(0).parentEdges().getFirst().parentRow());
        assertEquals(2, nodes.get(1).parentEdges().getFirst().parentRow());
        assertEquals(3, nodes.get(2).parentEdges().getFirst().parentRow());

        var window = layout.window(nodes, 1, 2);
        assertEquals(List.of(0, 1),
                window.stream().map(HistoryGraphLayout.Node::row).toList());
        assertEquals(1, window.getFirst().parentEdges().getFirst().parentRow());
        assertTrue(window.getLast().parentEdges().isEmpty());
    }

    private static HistorySnapshotPayload.Branch branch(
            String name, CommitId head, boolean active) {
        return new HistorySnapshotPayload.Branch(name, head, active);
    }

    private static HistorySnapshotPayload.Version version(
            char digit, long timestamp, List<CommitId> parents,
            Optional<UUID> zone) {
        return version(digit, timestamp, parents, zone, CommitKind.MANUAL);
    }

    private static HistorySnapshotPayload.Version version(
            char digit, long timestamp, List<CommitId> parents,
            Optional<UUID> zone, CommitKind kind) {
        return new HistorySnapshotPayload.Version(
                id(digit), "Save " + digit, "Builder", timestamp,
                kind, VersionTags.empty(), parents,
                new CommitStatistics(1, 0, 1, 0), zone);
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(
                String.valueOf(digit).repeat(ObjectId.HEX_LENGTH)));
    }
}
