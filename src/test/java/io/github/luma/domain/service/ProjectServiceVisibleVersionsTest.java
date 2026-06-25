package io.github.luma.domain.service;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectServiceVisibleVersionsTest {

    @TempDir
    Path tempDir;

    @Test
    void visibleVersionsHideDetachedHeadAfterAmend() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("history.mbp"));
        this.saveVersions(layout, List.of(
                version("v0001", "", 0),
                version("v0002", "v0001", 60),
                version("v0003", "v0001", 120)
        ));
        new VariantRepository().save(layout, List.of(new ProjectVariant("main", "Main", "v0001", "v0003", true, instant(0))));

        List<ProjectVersion> visible = new ProjectService().loadVisibleVersions(layout);

        assertEquals(List.of("v0001", "v0003"), visible.stream().map(ProjectVersion::id).toList());
    }

    @Test
    void visibleVersionsHideDetachedZoneHeadAfterAmend() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("zones.mbp"));
        this.saveVersions(layout, List.of(
                version("v0001", "", 0),
                zoneVersion("v0002", "v0001", 60),
                zoneVersion("v0003", "v0001", 120)
        ));
        new VariantRepository().save(layout, List.of(new ProjectVariant("main", "Main", "v0001", "v0003", true, instant(0))));

        List<ProjectVersion> visibleZones = new ProjectVersionVisibility()
                .workZoneHistory(new ProjectService().loadVisibleVersions(layout));

        assertEquals(List.of("v0003"), visibleZones.stream().map(ProjectVersion::id).toList());
    }

    private void saveVersions(ProjectLayout layout, List<ProjectVersion> versions) throws Exception {
        VersionRepository repository = new VersionRepository();
        for (ProjectVersion version : versions) {
            repository.save(layout, version);
        }
    }

    private static ProjectVersion version(String id, String parentVersionId, long offsetSeconds) {
        return version(id, parentVersionId, offsetSeconds, ExternalSourceInfo.manual());
    }

    private static ProjectVersion zoneVersion(String id, String parentVersionId, long offsetSeconds) {
        return version(id, parentVersionId, offsetSeconds, ExternalSourceInfo.external(
                "LUMI",
                "work-zone",
                "Work Zone Save",
                "tester",
                null,
                false,
                false,
                Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, "zone-a")
        ));
    }

    private static ProjectVersion version(String id, String parentVersionId, long offsetSeconds, ExternalSourceInfo sourceInfo) {
        return new ProjectVersion(
                id,
                "33333333-3333-3333-3333-333333333333",
                "main",
                parentVersionId,
                "",
                List.of(),
                VersionKind.MANUAL,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                sourceInfo,
                instant(offsetSeconds)
        );
    }

    private static Instant instant(long seconds) {
        return Instant.parse("2026-04-23T08:00:00Z").plusSeconds(seconds);
    }
}
