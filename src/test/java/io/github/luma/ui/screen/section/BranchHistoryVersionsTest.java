package io.github.luma.ui.screen.section;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchHistoryVersionsTest {

    @Test
    void includesSharedBaseCommitWhenBranchIsCreatedFromExistingCommit() {
        ProjectVariant branch = new ProjectVariant("feature", "Feature", "v0002", "v0002", false, instant(120));
        BranchHistoryVersions history = new BranchHistoryVersions();

        List<BranchHistoryVersions.Entry> entries = history.forVariant(
                List.of(
                        version("v0002", "main", "v0001", 120),
                        version("v0001", "main", "", 60)
                ),
                List.of(new ProjectVariant("main", "Main", "v0001", "v0002", true, instant(0)), branch),
                branch
        );

        assertEquals(List.of("v0002"), entries.stream().map(entry -> entry.version().id()).toList());
        assertEquals("feature", entries.getFirst().variant().id());
        assertTrue(entries.getFirst().current());
    }

    @Test
    void keepsSharedBaseCommitVisibleAfterBranchAddsNewSaves() {
        ProjectVariant branch = new ProjectVariant("feature", "Feature", "v0002", "v0003", false, instant(120));
        BranchHistoryVersions history = new BranchHistoryVersions();

        List<BranchHistoryVersions.Entry> entries = history.forVariant(
                List.of(
                        version("v0003", "feature", "v0002", 180),
                        version("v0002", "main", "v0001", 120),
                        version("v0001", "main", "", 60)
                ),
                List.of(new ProjectVariant("main", "Main", "v0001", "v0002", true, instant(0)), branch),
                branch
        );

        assertEquals(List.of("v0003", "v0002"), entries.stream().map(entry -> entry.version().id()).toList());
        assertEquals(List.of("feature", "feature"), entries.stream().map(entry -> entry.variant().id()).toList());
        assertEquals(List.of(true, false), entries.stream().map(BranchHistoryVersions.Entry::current).toList());
    }

    private static ProjectVersion version(String id, String variantId, String parentVersionId, long offsetSeconds) {
        return new ProjectVersion(
                id,
                "11111111-1111-1111-1111-111111111111",
                variantId,
                parentVersionId,
                "",
                List.of(),
                VersionKind.MANUAL,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                instant(offsetSeconds)
        );
    }

    private static Instant instant(long seconds) {
        return Instant.parse("2026-04-23T08:00:00Z").plusSeconds(seconds);
    }
}
