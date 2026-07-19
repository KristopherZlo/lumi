package io.github.lumi.domain.model;

/** Persistent builder-facing defaults owned by one workspace. */
public record WorkspaceSettings(
        boolean hideZoneCommits,
        boolean includeEntitiesOnRestore,
        boolean previewGenerationEnabled,
        boolean workspaceHudEnabled,
        boolean automaticVersionsEnabled) {
    public WorkspaceSettings(
            boolean hideZoneCommits, boolean includeEntitiesOnRestore) {
        this(hideZoneCommits, includeEntitiesOnRestore, true, true, false);
    }

    public static WorkspaceSettings defaults() {
        return new WorkspaceSettings(true, true, true, true, false);
    }
}
