package io.github.luma.domain.model;

public record MergeConflictZoneResolution(
        String zoneId,
        MergeConflictResolution resolution
) {
}
