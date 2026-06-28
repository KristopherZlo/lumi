package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.service.ProjectVersionVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

        assertEquals(List.of("v0002", "v0001"), entries.stream().map(entry -> entry.version().id()).toList());
        assertEquals(List.of("feature", "feature"), entries.stream().map(entry -> entry.variant().id()).toList());
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

        assertEquals(List.of("v0003", "v0002", "v0001"), entries.stream().map(entry -> entry.version().id()).toList());
        assertEquals(List.of("feature", "feature", "feature"), entries.stream().map(entry -> entry.variant().id()).toList());
        assertEquals(List.of(true, false, false), entries.stream().map(BranchHistoryVersions.Entry::current).toList());
    }

    @Test
    void includesReachableParentCommitsFromBranchHead() {
        ProjectVariant branch = new ProjectVariant("feature", "Feature", "v0003", "v0003", false, instant(180));
        BranchHistoryVersions history = new BranchHistoryVersions();

        List<BranchHistoryVersions.Entry> entries = history.forVariant(
                List.of(
                        version("v0003", "main", "v0002", 180),
                        version("v0002", "main", "v0001", 120),
                        version("v0001", "main", "", 60)
                ),
                List.of(new ProjectVariant("main", "Main", "v0001", "v0003", true, instant(0)), branch),
                branch
        );

        assertEquals(List.of("v0003", "v0002", "v0001"), entries.stream().map(entry -> entry.version().id()).toList());
        assertEquals(List.of("feature", "feature", "feature"), entries.stream().map(entry -> entry.variant().id()).toList());
        assertEquals(List.of(true, false, false), entries.stream().map(BranchHistoryVersions.Entry::current).toList());
    }

    @Test
    void globalHistoryCardsSkipZoneScopedVersionsEvenWhenTheyAreTheStoredHead() {
        ProjectVariant main = new ProjectVariant("main", "Main", "v0001", "v0003", true, instant(0));
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", 60),
                version("v0002", "main", "v0001", 120, Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, "zone-a")),
                version("v0003", "main", "v0002", 180, Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, "zone-a"))
        );

        List<String> visibleIds = new BranchHistoryVersions().forVariant(versions, List.of(main), main).stream()
                .map(entry -> entry.version().id())
                .toList();

        assertEquals(List.of("v0001"), visibleIds);
    }

    private static ProjectVersion version(String id, String variantId, String parentVersionId, long offsetSeconds) {
        return version(id, variantId, parentVersionId, offsetSeconds, Map.of());
    }

    private static ProjectVersion version(
            String id,
            String variantId,
            String parentVersionId,
            long offsetSeconds,
            Map<String, String> metadata
    ) {
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
                metadata.isEmpty()
                        ? ExternalSourceInfo.manual()
                        : ExternalSourceInfo.external("MANUAL", "manual", "Manual Save", "", null, false, false, metadata),
                instant(offsetSeconds)
        );
    }

    private static Instant instant(long seconds) {
        return Instant.parse("2026-04-23T08:00:00Z").plusSeconds(seconds);
    }
}
