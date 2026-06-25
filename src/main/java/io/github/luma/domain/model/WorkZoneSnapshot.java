package io.github.luma.domain.model;

import java.util.List;

public record WorkZoneSnapshot(
        BuildProject project,
        List<ProjectVariant> variants,
        List<ProjectVersion> versions,
        WorkZoneState zones,
        String actor,
        String focusedZoneId,
        PendingChangeSummary pendingChanges,
        String status
) {

    public WorkZoneSnapshot {
        variants = variants == null ? List.of() : List.copyOf(variants);
        versions = versions == null ? List.of() : List.copyOf(versions);
        zones = zones == null ? WorkZoneState.empty() : zones;
        actor = actor == null || actor.isBlank() ? "player" : actor;
        focusedZoneId = focusedZoneId == null ? "" : focusedZoneId;
        pendingChanges = pendingChanges == null ? PendingChangeSummary.empty() : pendingChanges;
        status = status == null || status.isBlank() ? "luma.status.zones_ready" : status;
    }

    public static WorkZoneSnapshot empty(String status) {
        return new WorkZoneSnapshot(
                null,
                List.of(),
                List.of(),
                WorkZoneState.empty(),
                "player",
                "",
                PendingChangeSummary.empty(),
                status
        );
    }
}
