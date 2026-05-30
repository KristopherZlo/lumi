package io.github.luma.domain.model;

import java.time.Instant;

public record PendingRestoreCompletion(
        String projectId,
        String variantId,
        String targetVersionId,
        PendingRestoreCompletionKind kind,
        Instant createdAt,
        Bounds3i partialBounds,
        PartialRestoreMode partialMode
) {

    public PendingRestoreCompletion {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("Pending restore completion requires project id");
        }
        if (variantId == null || variantId.isBlank()) {
            throw new IllegalArgumentException("Pending restore completion requires variant id");
        }
        if (targetVersionId == null || targetVersionId.isBlank()) {
            throw new IllegalArgumentException("Pending restore completion requires target version id");
        }
        kind = kind == null ? PendingRestoreCompletionKind.FULL_RESTORE : kind;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        partialMode = partialMode == null ? PartialRestoreMode.SELECTED_AREA : partialMode;
    }

    public static PendingRestoreCompletion full(
            String projectId,
            String variantId,
            String targetVersionId,
            Instant createdAt
    ) {
        return new PendingRestoreCompletion(
                projectId,
                variantId,
                targetVersionId,
                PendingRestoreCompletionKind.FULL_RESTORE,
                createdAt,
                null,
                PartialRestoreMode.SELECTED_AREA
        );
    }

    public static PendingRestoreCompletion partial(
            String projectId,
            String variantId,
            String targetVersionId,
            Instant createdAt,
            Bounds3i partialBounds,
            PartialRestoreMode partialMode
    ) {
        if (partialBounds == null) {
            throw new IllegalArgumentException("Pending partial restore completion requires bounds");
        }
        return new PendingRestoreCompletion(
                projectId,
                variantId,
                targetVersionId,
                PendingRestoreCompletionKind.PARTIAL_RESTORE,
                createdAt,
                partialBounds,
                partialMode
        );
    }
}
