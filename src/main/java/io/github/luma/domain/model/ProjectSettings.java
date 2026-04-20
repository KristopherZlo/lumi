package io.github.luma.domain.model;

public record ProjectSettings(
        boolean autoVersionsEnabled,
        int autoVersionMinutes,
        int sessionIdleSeconds,
        int snapshotEveryVersions,
        double snapshotVolumeThreshold
) {

    public static ProjectSettings defaults() {
        return new ProjectSettings(false, 10, 5, 10, 0.20D);
    }
}
