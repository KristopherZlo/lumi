package io.github.luma.domain.model;

public record ProjectSettings(
        boolean autoVersionsEnabled,
        int autoVersionMinutes,
        int sessionIdleSeconds,
        int snapshotEveryVersions,
        double snapshotVolumeThreshold,
        boolean safetySnapshotBeforeRestore,
        boolean previewGenerationEnabled,
        boolean debugLoggingEnabled,
        boolean autoCheckpointEnabled,
        int autoCheckpointLargeChangeThreshold,
        Boolean workspaceHudEnabled,
        Boolean showHiddenCommits,
        Boolean survivalModeEnabled
) {
    public static final int DEFAULT_AUTO_CHECKPOINT_LARGE_CHANGE_THRESHOLD = 512;

    public ProjectSettings(
            boolean autoVersionsEnabled,
            int autoVersionMinutes,
            int sessionIdleSeconds,
            int snapshotEveryVersions,
            double snapshotVolumeThreshold,
            boolean safetySnapshotBeforeRestore,
            boolean previewGenerationEnabled,
            boolean debugLoggingEnabled,
            boolean autoCheckpointEnabled,
            int autoCheckpointLargeChangeThreshold,
            Boolean workspaceHudEnabled
    ) {
        this(
                autoVersionsEnabled,
                autoVersionMinutes,
                sessionIdleSeconds,
                snapshotEveryVersions,
                snapshotVolumeThreshold,
                safetySnapshotBeforeRestore,
                previewGenerationEnabled,
                debugLoggingEnabled,
                autoCheckpointEnabled,
                autoCheckpointLargeChangeThreshold,
                workspaceHudEnabled,
                false,
                false
        );
    }

    public ProjectSettings(
            boolean autoVersionsEnabled,
            int autoVersionMinutes,
            int sessionIdleSeconds,
            int snapshotEveryVersions,
            double snapshotVolumeThreshold,
            boolean safetySnapshotBeforeRestore,
            boolean previewGenerationEnabled,
            boolean debugLoggingEnabled,
            boolean autoCheckpointEnabled,
            int autoCheckpointLargeChangeThreshold,
            Boolean workspaceHudEnabled,
            Boolean showHiddenCommits
    ) {
        this(
                autoVersionsEnabled,
                autoVersionMinutes,
                sessionIdleSeconds,
                snapshotEveryVersions,
                snapshotVolumeThreshold,
                safetySnapshotBeforeRestore,
                previewGenerationEnabled,
                debugLoggingEnabled,
                autoCheckpointEnabled,
                autoCheckpointLargeChangeThreshold,
                workspaceHudEnabled,
                showHiddenCommits,
                false
        );
    }

    public static ProjectSettings defaults() {
        return new ProjectSettings(
                false,
                10,
                5,
                10,
                0.20D,
                true,
                true,
                false,
                false,
                DEFAULT_AUTO_CHECKPOINT_LARGE_CHANGE_THRESHOLD,
                true,
                false,
                false
        );
    }

    public static ProjectSettings sanitize(ProjectSettings settings) {
        if (settings == null) {
            return defaults();
        }

        return new ProjectSettings(
                settings.autoVersionsEnabled(),
                settings.autoVersionMinutes() <= 0 ? 10 : settings.autoVersionMinutes(),
                settings.sessionIdleSeconds() <= 0 ? 5 : settings.sessionIdleSeconds(),
                settings.snapshotEveryVersions() <= 0 ? 10 : settings.snapshotEveryVersions(),
                settings.snapshotVolumeThreshold() <= 0.0D ? 0.20D : settings.snapshotVolumeThreshold(),
                settings.safetySnapshotBeforeRestore(),
                settings.previewGenerationEnabled(),
                settings.debugLoggingEnabled(),
                settings.autoCheckpointEnabled(),
                settings.autoCheckpointLargeChangeThreshold() <= 0
                        ? DEFAULT_AUTO_CHECKPOINT_LARGE_CHANGE_THRESHOLD
                        : settings.autoCheckpointLargeChangeThreshold(),
                settings.workspaceHudEnabled() == null ? true : settings.workspaceHudEnabled(),
                settings.showHiddenCommits() == null ? false : settings.showHiddenCommits(),
                settings.survivalModeEnabled() == null ? false : settings.survivalModeEnabled()
        );
    }

    public boolean workspaceHudVisible() {
        return !Boolean.FALSE.equals(this.workspaceHudEnabled);
    }

    public boolean hiddenCommitsVisible() {
        return Boolean.TRUE.equals(this.showHiddenCommits);
    }

    public boolean survivalModeAllowed() {
        return Boolean.TRUE.equals(this.survivalModeEnabled);
    }
}
