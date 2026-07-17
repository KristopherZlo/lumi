package io.github.lumi.network;

import io.github.lumi.domain.model.WorkspaceSettings;
import java.util.Objects;

/** Canonical trust-boundary encoding for active-workspace settings. */
public record WorkspaceSettingsArgument(
        boolean hideZoneCommits,
        boolean includeEntitiesOnRestore) {
    public WorkspaceSettingsArgument(WorkspaceSettings settings) {
        this(
                Objects.requireNonNull(settings, "settings").hideZoneCommits(),
                settings.includeEntitiesOnRestore());
    }

    public String encode() {
        return flag(hideZoneCommits) + "," + flag(includeEntitiesOnRestore);
    }

    public WorkspaceSettings settings() {
        return new WorkspaceSettings(hideZoneCommits, includeEntitiesOnRestore);
    }

    public static WorkspaceSettingsArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length() != 3 || encoded.charAt(1) != ',') {
            throw new IllegalArgumentException("Invalid workspace settings argument");
        }
        return new WorkspaceSettingsArgument(
                parseFlag(encoded.charAt(0)), parseFlag(encoded.charAt(2)));
    }

    private static char flag(boolean value) {
        return value ? '1' : '0';
    }

    private static boolean parseFlag(char value) {
        if (value == '1') return true;
        if (value == '0') return false;
        throw new IllegalArgumentException("Invalid workspace settings flag");
    }
}
