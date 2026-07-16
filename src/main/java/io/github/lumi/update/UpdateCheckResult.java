package io.github.lumi.update;

import java.util.Objects;
import java.util.Optional;

public record UpdateCheckResult(Status status, Optional<UpdateRelease> release) {
    public UpdateCheckResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(release, "release");
        if ((status == Status.UPDATE_AVAILABLE) != release.isPresent()) {
            throw new IllegalArgumentException("Only available updates carry a release");
        }
    }

    public static UpdateCheckResult available(UpdateRelease release) {
        return new UpdateCheckResult(Status.UPDATE_AVAILABLE, Optional.of(release));
    }

    public static UpdateCheckResult upToDate() {
        return new UpdateCheckResult(Status.UP_TO_DATE, Optional.empty());
    }

    public static UpdateCheckResult failed() {
        return new UpdateCheckResult(Status.FAILED, Optional.empty());
    }

    public enum Status {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        FAILED
    }
}
