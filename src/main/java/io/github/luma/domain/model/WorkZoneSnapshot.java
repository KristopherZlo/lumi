package io.github.luma.domain.model;

import java.util.List;

public record WorkZoneSnapshot(
        BuildProject project,
        List<ProjectVariant> variants,
        WorkZoneState zones,
        String actor,
        String focusedZoneId,
        String status
) {

    public WorkZoneSnapshot {
        variants = variants == null ? List.of() : List.copyOf(variants);
        zones = zones == null ? WorkZoneState.empty() : zones;
        actor = actor == null || actor.isBlank() ? "player" : actor;
        focusedZoneId = focusedZoneId == null ? "" : focusedZoneId;
        status = status == null || status.isBlank() ? "luma.status.zones_ready" : status;
    }

    public static WorkZoneSnapshot empty(String status) {
        return new WorkZoneSnapshot(null, List.of(), WorkZoneState.empty(), "player", "", status);
    }
}
