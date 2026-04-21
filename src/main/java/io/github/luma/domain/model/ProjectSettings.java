package io.github.luma.domain.model;

public record ProjectSettings(
        boolean autoVersionsEnabled,
        int autoVersionMinutes,
        int sessionIdleSeconds,
        int snapshotEveryVersions,
        double snapshotVolumeThreshold,
        boolean safetySnapshotBeforeRestore,
        boolean previewGenerationEnabled,
        boolean debugLoggingEnabled
) {

    public static ProjectSettings defaults() {
        return new ProjectSettings(false, 10, 5, 10, 0.20D, true, true, false);
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
                settings.debugLoggingEnabled()
        );
    }
}
