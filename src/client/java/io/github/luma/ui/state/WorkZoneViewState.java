package io.github.luma.ui.state;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.WorkZoneSnapshot;
import io.github.luma.domain.model.WorkZoneState;
import java.util.List;

public record WorkZoneViewState(
        BuildProject project,
        List<ProjectVariant> variants,
        List<ProjectVersion> versions,
        WorkZoneState zones,
        String actor,
        String focusedZoneId,
        PendingChangeSummary pendingChanges,
        String status
) {

    public WorkZoneViewState {
        variants = variants == null ? List.of() : List.copyOf(variants);
        versions = versions == null ? List.of() : List.copyOf(versions);
        zones = zones == null ? WorkZoneState.empty() : zones;
        actor = actor == null || actor.isBlank() ? "player" : actor;
        focusedZoneId = focusedZoneId == null ? "" : focusedZoneId;
        pendingChanges = pendingChanges == null ? PendingChangeSummary.empty() : pendingChanges;
        status = status == null || status.isBlank() ? "luma.status.zones_ready" : status;
    }

    public WorkZoneViewState(
            BuildProject project,
            List<ProjectVariant> variants,
            List<ProjectVersion> versions,
            WorkZoneState zones,
            String actor,
            String focusedZoneId,
            String status
    ) {
        this(project, variants, versions, zones, actor, focusedZoneId, PendingChangeSummary.empty(), status);
    }

    public static WorkZoneViewState fromSnapshot(WorkZoneSnapshot snapshot) {
        if (snapshot == null) {
            return new WorkZoneViewState(null, List.of(), List.of(), WorkZoneState.empty(), "player", "", "luma.status.zones_loading");
        }
        return new WorkZoneViewState(
                snapshot.project(),
                snapshot.variants(),
                snapshot.versions(),
                snapshot.zones(),
                snapshot.actor(),
                snapshot.focusedZoneId(),
                snapshot.pendingChanges(),
                snapshot.status()
        );
    }
}
