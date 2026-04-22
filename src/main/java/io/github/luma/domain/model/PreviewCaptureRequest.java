package io.github.luma.domain.model;

import java.time.Instant;

public record PreviewCaptureRequest(
        String versionId,
        String dimensionId,
        Bounds3i bounds,
        Instant requestedAt
) {
}
