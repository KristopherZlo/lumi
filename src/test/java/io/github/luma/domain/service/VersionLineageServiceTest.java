package io.github.luma.domain.service;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionLineageServiceTest {

    private final VersionLineageService lineageService = new VersionLineageService();

    @Test
    void reachableVersionIdsHideDetachedDescendantsAfterReset() {
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", Instant.parse("2026-04-21T10:00:00Z")),
                version("v0002", "main", "v0001", Instant.parse("2026-04-21T10:01:00Z")),
                version("v0003", "main", "v0002", Instant.parse("2026-04-21T10:02:00Z"))
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0001", true, Instant.parse("2026-04-21T10:00:00Z"))
        );

        Set<String> reachable = this.lineageService.reachableVersionIds(versions, variants);

        assertEquals(Set.of("v0001"), reachable);
        assertFalse(reachable.contains("v0002"));
        assertFalse(reachable.contains("v0003"));
    }

    @Test
    void reachableVersionIdsIncludeAllLiveBranchHeads() {
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", Instant.parse("2026-04-21T10:00:00Z")),
                version("v0002", "main", "v0001", Instant.parse("2026-04-21T10:01:00Z")),
                version("v0003", "feature", "v0002", Instant.parse("2026-04-21T10:02:00Z"))
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, Instant.parse("2026-04-21T10:00:00Z")),
                new ProjectVariant("feature", "feature", "v0002", "v0003", false, Instant.parse("2026-04-21T10:02:00Z"))
        );

        Set<String> reachable = this.lineageService.reachableVersionIds(versions, variants);

        assertTrue(reachable.contains("v0001"));
        assertTrue(reachable.contains("v0002"));
        assertTrue(reachable.contains("v0003"));
    }

    @Test
    void resolveVariantHeadReturnsHeadVersion() {
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", Instant.parse("2026-04-21T10:00:00Z")),
                version("v0002", "main", "v0001", Instant.parse("2026-04-21T10:01:00Z"))
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, Instant.parse("2026-04-21T10:00:00Z"))
        );

        ProjectVersion resolved = this.lineageService.resolveVariantHead(versions, variants, "main");

        assertNotNull(resolved);
        assertEquals("v0002", resolved.id());
    }

    private static ProjectVersion version(String id, String variantId, String parentVersionId, Instant createdAt) {
        return new ProjectVersion(
                id,
                "project",
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
                createdAt
        );
    }
}
