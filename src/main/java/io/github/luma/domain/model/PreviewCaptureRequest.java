package io.github.luma.domain.model;

import java.time.Duration;
import java.time.Instant;

public record PreviewCaptureRequest(
        String versionId,
        String dimensionId,
        Bounds3i bounds,
        Instant requestedAt,
        int attempts,
        Instant nextAttemptAt,
        String lastFailure
) {

    public PreviewCaptureRequest {
        attempts = Math.max(0, attempts);
        lastFailure = lastFailure == null ? "" : lastFailure;
    }

    public PreviewCaptureRequest(String versionId, String dimensionId, Bounds3i bounds, Instant requestedAt) {
        this(versionId, dimensionId, bounds, requestedAt, 0, null, "");
    }

    public boolean dueAt(Instant now) {
        if (this.nextAttemptAt == null) {
            return true;
        }
        Instant reference = now == null ? Instant.now() : now;
        return !this.nextAttemptAt.isAfter(reference);
    }

    public PreviewCaptureRequest withFailure(Instant failedAt, Duration retryDelay, String failure) {
        Instant failureTime = failedAt == null ? Instant.now() : failedAt;
        Duration delay = retryDelay == null || retryDelay.isNegative() ? Duration.ZERO : retryDelay;
        return new PreviewCaptureRequest(
                this.versionId,
                this.dimensionId,
                this.bounds,
                this.requestedAt,
                Math.max(0, this.attempts) + 1,
                failureTime.plus(delay),
                failure == null ? "" : failure
        );
    }
}
