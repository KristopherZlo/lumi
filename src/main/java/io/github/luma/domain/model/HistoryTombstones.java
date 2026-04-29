package io.github.luma.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Soft-delete metadata for project history.
 */
public record HistoryTombstones(
        int schemaVersion,
        List<String> deletedVersionIds,
        List<String> deletedVariantIds,
        Instant updatedAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static HistoryTombstones empty() {
        return new HistoryTombstones(CURRENT_SCHEMA_VERSION, List.of(), List.of(), null);
    }

    public HistoryTombstones {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        deletedVersionIds = normalize(deletedVersionIds);
        deletedVariantIds = normalize(deletedVariantIds);
    }

    public boolean versionDeleted(String versionId) {
        return versionId != null && this.deletedVersionIds.contains(versionId);
    }

    public boolean variantDeleted(String variantId) {
        return variantId != null && this.deletedVariantIds.contains(variantId);
    }

    public HistoryTombstones withDeletedVersion(String versionId, Instant now) {
        if (versionId == null || versionId.isBlank() || this.versionDeleted(versionId)) {
            return this;
        }
        LinkedHashSet<String> next = new LinkedHashSet<>(this.deletedVersionIds);
        next.add(versionId);
        return new HistoryTombstones(CURRENT_SCHEMA_VERSION, List.copyOf(next), this.deletedVariantIds, now);
    }

    public HistoryTombstones withDeletedVariant(String variantId, Instant now) {
        if (variantId == null || variantId.isBlank() || this.variantDeleted(variantId)) {
            return this;
        }
        LinkedHashSet<String> next = new LinkedHashSet<>(this.deletedVariantIds);
        next.add(variantId);
        return new HistoryTombstones(CURRENT_SCHEMA_VERSION, this.deletedVersionIds, List.copyOf(next), now);
    }

    private static List<String> normalize(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                normalized.add(id);
            }
        }
        return List.copyOf(normalized);
    }
}
