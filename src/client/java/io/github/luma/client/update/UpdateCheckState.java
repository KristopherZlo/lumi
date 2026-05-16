package io.github.luma.client.update;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public record UpdateCheckState(
        Instant lastCheckedAt,
        UpdateRelease availableRelease,
        String dismissedVersion
) {

    public UpdateCheckState {
        dismissedVersion = dismissedVersion == null ? "" : dismissedVersion.trim();
    }

    public static UpdateCheckState empty() {
        return new UpdateCheckState(null, null, "");
    }

    public boolean shouldCheck(Instant now, Duration interval) {
        if (this.lastCheckedAt == null) {
            return true;
        }
        Duration normalizedInterval = interval == null || interval.isNegative()
                ? Duration.ZERO
                : interval;
        return this.lastCheckedAt.plus(normalizedInterval).isBefore(now)
                || this.lastCheckedAt.plus(normalizedInterval).equals(now);
    }

    public Optional<UpdateRelease> promptRelease() {
        if (this.availableRelease == null || this.availableRelease.version().isBlank()) {
            return Optional.empty();
        }
        if (this.availableRelease.version().equals(this.dismissedVersion)) {
            return Optional.empty();
        }
        return Optional.of(this.availableRelease);
    }

    public UpdateCheckState withChecked(Instant checkedAt, UpdateCheckResult result) {
        UpdateRelease release = result != null && result.available() ? result.release() : null;
        return new UpdateCheckState(checkedAt, release, this.dismissedVersion);
    }

    public UpdateCheckState withFailedCheck(Instant checkedAt) {
        return new UpdateCheckState(checkedAt, this.availableRelease, this.dismissedVersion);
    }

    public UpdateCheckState withDismissedVersion(String version) {
        return new UpdateCheckState(this.lastCheckedAt, this.availableRelease, version);
    }
}
