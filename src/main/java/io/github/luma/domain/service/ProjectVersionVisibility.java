package io.github.luma.domain.service;

import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.WorkZone;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps project-level and zone-scoped version lists from leaking into each other.
 */
public final class ProjectVersionVisibility {

    public static final String WORK_ZONE_ID_METADATA = "workZoneId";

    public List<ProjectVersion> globalHistory(List<ProjectVersion> versions) {
        return safe(versions).stream()
                .filter(version -> this.workZoneId(version).isBlank())
                .toList();
    }

    public List<ProjectVersion> globalHistory(List<ProjectVersion> versions, List<WorkZone> zones, boolean showHiddenCommits) {
        if (showHiddenCommits) {
            return safe(versions);
        }
        Set<String> activeZoneIds = (zones == null ? List.<WorkZone>of() : zones).stream()
                .map(WorkZone::id)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return safe(versions).stream()
                .filter(version -> {
                    String workZoneId = this.workZoneId(version);
                    return workZoneId.isBlank() || !activeZoneIds.contains(workZoneId);
                })
                .toList();
    }

    public List<ProjectVersion> workZoneHistory(List<ProjectVersion> versions) {
        return safe(versions).stream()
                .filter(version -> !this.workZoneId(version).isBlank())
                .toList();
    }

    public List<ProjectVersion> zoneHistory(List<ProjectVersion> versions, String zoneId) {
        String expected = zoneId == null ? "" : zoneId;
        if (expected.isBlank()) {
            return List.of();
        }
        return safe(versions).stream()
                .filter(version -> expected.equals(this.workZoneId(version)))
                .toList();
    }

    public String workZoneId(ProjectVersion version) {
        if (version == null || version.sourceInfo() == null) {
            return "";
        }
        Map<String, String> metadata = version.sourceInfo().metadata();
        if (metadata == null) {
            return "";
        }
        String zoneId = metadata.get(WORK_ZONE_ID_METADATA);
        return zoneId == null ? "" : zoneId;
    }

    private static List<ProjectVersion> safe(List<ProjectVersion> versions) {
        return versions == null ? List.of() : versions;
    }
}
