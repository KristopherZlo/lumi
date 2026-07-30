package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class HistoryViewControllerTest {
    private final HistoryViewController controller =
            new HistoryViewController(new HistoryScope.Workspace());

    @Test
    void defaultsToCardsAndCyclesAllThenEveryBranch() {
        var branches = List.of(
                branch("main", 'c', true),
                branch("tower", 'd', false));

        assertEquals(HistoryViewController.Mode.CARDS, controller.mode());
        assertEquals(new HistoryScope.Workspace(), controller.scope());
        assertEquals("", controller.branch());
        controller.show(HistoryViewController.Mode.GRAPH);
        controller.nextBranch(branches);
        assertEquals(HistoryViewController.Mode.GRAPH, controller.mode());
        assertEquals("main", controller.branch());
        controller.nextBranch(branches);
        assertEquals("tower", controller.branch());
        controller.nextBranch(branches);
        assertEquals("", controller.branch());
    }

    @Test
    void initialSaveIsImmutableAndNeverFeatured() {
        var initial = new HistorySnapshotPayload.Version(
                id('a'), "Initial world", "Lumi", 0,
                CommitKind.HIDDEN_SAFETY, VersionTags.empty(), List.of(),
                new CommitStatistics(0, 0, 0, 0), Optional.empty());

        assertTrue(VersionText.immutable(initial));
        assertFalse(VersionText.featured(initial));
    }

    @Test
    void prefetchesOneViewportBeforeTheLoadedTail() {
        assertFalse(HistoryViewController.shouldPrefetch(20, 10, 64));
        assertTrue(HistoryViewController.shouldPrefetch(44, 10, 64));
    }

    @Test
    void sharesTagSearchAndMergeAncestryAcrossViews() {
        var root = version('a', "Root", List.of(), "base");
        var left = version('b', "Left", List.of(root.id()), "stone");
        var right = version('c', "Right", List.of(root.id()), "copper");
        var merge = version('d', "Merge", List.of(left.id(), right.id()), "roof");
        var stray = version('e', "Stray", List.of(), "stone");
        var snapshot = snapshot(
                List.of(merge, right, left, root, stray),
                List.of(branch("main", 'd', true), branch("stray", 'e', false)));

        controller.nextBranch(snapshot.branches());
        assertEquals(List.of(left), controller.visible(snapshot, "stone"));
        assertEquals(List.of(merge, right, left, root),
                controller.visible(snapshot, ""));
    }

    private static HistorySnapshotPayload snapshot(
            List<HistorySnapshotPayload.Version> versions,
            List<HistorySnapshotPayload.Branch> branches) {
        return new HistorySnapshotPayload(
                "minecraft:overworld", id('d'), 1, 0, false, false,
                UUID.randomUUID(), "Build", "main", versions, branches,
                List.of(), List.of());
    }

    private static HistorySnapshotPayload.Branch branch(
            String name, char head, boolean active) {
        return new HistorySnapshotPayload.Branch(name, id(head), active);
    }

    private static HistorySnapshotPayload.Version version(
            char digit, String message, List<CommitId> parents, String tag) {
        return new HistorySnapshotPayload.Version(
                id(digit), message, "Builder", digit, CommitKind.MANUAL,
                new VersionTags(List.of(tag)), parents,
                new CommitStatistics(1, 0, 1, 0), Optional.empty());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(
                String.valueOf(digit).repeat(ObjectId.HEX_LENGTH)));
    }
}
