package io.github.luma.domain.model;

import java.time.Instant;

public record OperationHandle(
        String id,
        String projectId,
        String label,
        Instant startedAt,
        boolean debugEnabled
) {
}
