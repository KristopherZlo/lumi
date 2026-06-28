package io.github.luma.domain.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public record RestoreEntityTypeSelection(Set<String> excludedEntityTypes) {

    public RestoreEntityTypeSelection {
        excludedEntityTypes = normalize(excludedEntityTypes);
    }

    public static RestoreEntityTypeSelection includeAll() {
        return new RestoreEntityTypeSelection(Set.of());
    }

    public static RestoreEntityTypeSelection excludeTypes(Collection<String> entityTypes) {
        return new RestoreEntityTypeSelection(entityTypes == null ? Set.of() : new LinkedHashSet<>(entityTypes));
    }

    public boolean includes(String entityType) {
        return entityType == null || !this.excludedEntityTypes.contains(entityType);
    }

    private static Set<String> normalize(Collection<String> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String entityType : entityTypes) {
            if (entityType != null && !entityType.isBlank()) {
                normalized.add(entityType);
            }
        }
        return Set.copyOf(normalized);
    }
}
