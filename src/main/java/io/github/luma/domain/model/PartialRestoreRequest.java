package io.github.luma.domain.model;

import java.util.Map;

public record PartialRestoreRequest(
        String projectName,
        String targetVersionId,
        Bounds3i bounds,
        PartialRestoreMode restoreMode,
        PartialRestoreRegionSource regionSource,
        String actor,
        Map<String, String> metadata
) {

    public PartialRestoreRequest {
        projectName = projectName == null ? "" : projectName;
        targetVersionId = targetVersionId == null ? "" : targetVersionId;
        restoreMode = restoreMode == null ? PartialRestoreMode.SELECTED_AREA : restoreMode;
        regionSource = regionSource == null ? PartialRestoreRegionSource.MANUAL_BOUNDS : regionSource;
        actor = actor == null ? "" : actor;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public PartialRestoreRequest(
            String projectName,
            String targetVersionId,
            Bounds3i bounds,
            PartialRestoreRegionSource regionSource,
            String actor,
            Map<String, String> metadata
    ) {
        this(projectName, targetVersionId, bounds, PartialRestoreMode.SELECTED_AREA, regionSource, actor, metadata);
    }
}
