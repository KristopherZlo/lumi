package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.WorkZoneSnapshot;
import io.github.luma.domain.model.WorkZoneState;
import java.util.List;

public record WorkZoneViewState(
        BuildProject project,
        List<ProjectVariant> variants,
        WorkZoneState zones,
        String actor,
        String focusedZoneId,
        String status
) {

    public WorkZoneViewState {
        variants = variants == null ? List.of() : List.copyOf(variants);
        zones = zones == null ? WorkZoneState.empty() : zones;
        actor = actor == null || actor.isBlank() ? "player" : actor;
        focusedZoneId = focusedZoneId == null ? "" : focusedZoneId;
        status = status == null || status.isBlank() ? "luma.status.zones_ready" : status;
    }

    public static WorkZoneViewState fromSnapshot(WorkZoneSnapshot snapshot) {
        if (snapshot == null) {
            return new WorkZoneViewState(null, List.of(), WorkZoneState.empty(), "player", "", "luma.status.zones_loading");
        }
        return new WorkZoneViewState(
                snapshot.project(),
                snapshot.variants(),
                snapshot.zones(),
                snapshot.actor(),
                snapshot.focusedZoneId(),
                snapshot.status()
        );
    }
}
