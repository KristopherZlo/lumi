package io.github.luma.domain.service;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    @Test
    void resolvesCommonAncestorAndPathFromAncestor() {
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", Instant.parse("2026-04-21T10:00:00Z")),
                version("v0002", "main", "v0001", Instant.parse("2026-04-21T10:01:00Z")),
                version("v0003", "main", "v0002", Instant.parse("2026-04-21T10:02:00Z")),
                version("v0004", "feature", "v0002", Instant.parse("2026-04-21T10:03:00Z"))
        );
        Map<String, ProjectVersion> versionMap = this.lineageService.versionMap(versions);

        ProjectVersion ancestor = this.lineageService.commonAncestor(
                versionMap,
                versionMap.get("v0003"),
                versionMap.get("v0004")
        );
        List<ProjectVersion> path = this.lineageService.pathFromAncestor(
                versionMap,
                ancestor,
                versionMap.get("v0003")
        );

        assertEquals("v0002", ancestor.id());
        assertEquals(List.of("v0003"), path.stream().map(ProjectVersion::id).toList());
        assertTrue(this.lineageService.isAncestor(versionMap, "v0002", "v0003"));
        assertFalse(this.lineageService.isAncestor(versionMap, "v0004", "v0003"));
    }

    @Test
    void resolvesSharedSavedAncestorAcrossImportedMaps() {
        ProjectVersion root = version("v0001", "main", "", Instant.parse("2026-04-21T10:00:00Z"));
        ProjectVersion sourceHead = version("v0002", "feature", "v0001", Instant.parse("2026-04-21T10:01:00Z"));
        Map<String, ProjectVersion> targetVersionMap = this.lineageService.versionMap(List.of(root));
        Map<String, ProjectVersion> sourceVersionMap = this.lineageService.versionMap(List.of(root, sourceHead));

        String ancestorId = this.lineageService.sharedSavedAncestorId(targetVersionMap, sourceVersionMap, "v0002");

        assertEquals("v0001", ancestorId);
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
