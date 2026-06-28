package io.github.luma.domain.model;

public record RestoreEntityTypeCount(
        String entityType,
        int count
) {

    public RestoreEntityTypeCount {
        entityType = entityType == null || entityType.isBlank() ? "unknown:entity" : entityType;
        count = Math.max(0, count);
    }
}
