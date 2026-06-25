package io.github.luma.domain.service;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectVersionVisibilityTest {

    private final ProjectVersionVisibility visibility = new ProjectVersionVisibility();

    @Test
    void globalHistoryExcludesWorkZoneVersions() {
        List<ProjectVersion> versions = List.of(
                version("v0001", Map.of()),
                version("v0002", Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, "zone-a")),
                version("v0003", Map.of())
        );

        assertEquals(List.of("v0001", "v0003"), this.visibility.globalHistory(versions).stream()
                .map(ProjectVersion::id)
                .toList());
    }

    @Test
    void zoneHistoryIncludesOnlyRequestedZone() {
        List<ProjectVersion> versions = List.of(
                version("v0001", Map.of()),
                version("v0002", Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, "zone-a")),
                version("v0003", Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, "zone-b")),
                version("v0004", Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, "zone-a"))
        );

        assertEquals(List.of("v0002", "v0004"), this.visibility.zoneHistory(versions, "zone-a").stream()
                .map(ProjectVersion::id)
                .toList());
    }

    private static ProjectVersion version(String id, Map<String, String> metadata) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                "",
                "",
                List.of(),
                VersionKind.MANUAL,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.external("MANUAL", "manual", "Manual Save", "", null, false, false, metadata),
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}
