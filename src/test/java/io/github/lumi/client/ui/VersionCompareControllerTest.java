package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionCompareControllerTest {
    @Test
    void comparesASelectedVersionWithItsRecordedFirstParent() {
        var newest = version('2', "newest", id('1'));
        var unrelated = version('9', "unrelated");
        var parent = version('1', "parent");

        var target = new VersionCompareController()
                .target(List.of(newest, unrelated, parent), 0)
                .orElseThrow();

        assertEquals(parent.id(), target.before());
        assertEquals(newest.id(), target.after());
        assertEquals("newest", target.label());
    }

    @Test
    void rootVersionHasNoComparisonTarget() {
        assertTrue(new VersionCompareController()
                .target(List.of(version('1', "root")), 0)
                .isEmpty());
    }

    @Test
    void comparesAnyTwoDistinctRowsInTheirSelectedOrder() {
        var versions = List.of(
                version('3', "newest"),
                version('2', "middle"),
                version('1', "oldest"));

        var target = new VersionCompareController()
                .target(versions, 2, 0)
                .orElseThrow();

        assertEquals(versions.get(2).id(), target.before());
        assertEquals(versions.get(0).id(), target.after());
        assertEquals("newest", target.label());
    }

    @Test
    void rejectsEqualOrOutOfBoundsRows() {
        var versions = List.of(version('1', "only"));
        var controller = new VersionCompareController();

        assertTrue(controller.target(versions, 0, 0).isEmpty());
        assertTrue(controller.target(versions, -1, 0).isEmpty());
        assertTrue(controller.target(versions, 0, 1).isEmpty());
    }

    @Test
    void comparesIndependentSelectionsAfterColumnFiltering() {
        var before = version('1', "left branch");
        var after = version('2', "right branch");

        var target = new VersionCompareController()
                .target(before, after)
                .orElseThrow();

        assertEquals(before.id(), target.before());
        assertEquals(after.id(), target.after());
        assertEquals(after.message(), target.label());
    }

    private static HistorySnapshotPayload.Version version(
            char digit, String message, CommitId... parents) {
        return new HistorySnapshotPayload.Version(
                id(digit), message, "Builder", 1L, CommitKind.MANUAL,
                io.github.lumi.domain.model.VersionTags.empty(),
                List.of(parents),
                new io.github.lumi.domain.model.CommitStatistics(0, 0, 0, 0),
                java.util.Optional.empty());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(
                String.valueOf(digit).repeat(ObjectId.HEX_LENGTH)));
    }
}
