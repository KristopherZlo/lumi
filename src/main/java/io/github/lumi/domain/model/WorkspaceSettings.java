package io.github.lumi.domain.model;

/** Persistent builder-facing defaults owned by one workspace. */
public record WorkspaceSettings(
        boolean hideZoneCommits,
        boolean includeEntitiesOnRestore) {
    public static WorkspaceSettings defaults() {
        return new WorkspaceSettings(true, true);
    }
}
