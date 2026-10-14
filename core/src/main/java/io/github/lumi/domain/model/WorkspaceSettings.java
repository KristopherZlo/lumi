package io.github.lumi.domain.model;

import java.util.Objects;

/** Persistent builder-facing defaults owned by one workspace. */
public record WorkspaceSettings(
        boolean hideZoneCommits,
        boolean includeEntitiesOnRestore,
        boolean previewGenerationEnabled,
        HudDisplayMode hudDisplayMode,
        boolean automaticVersionsEnabled) {
    public WorkspaceSettings {
        Objects.requireNonNull(hudDisplayMode, "hudDisplayMode");
    }

    public WorkspaceSettings(
            boolean hideZoneCommits, boolean includeEntitiesOnRestore) {
        this(hideZoneCommits, includeEntitiesOnRestore, true,
                HudDisplayMode.GUI, false);
    }

    public static WorkspaceSettings defaults() {
        return new WorkspaceSettings(
                true, true, true, HudDisplayMode.GUI, false);
    }
}
