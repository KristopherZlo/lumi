package io.github.luma.integration.common;

import java.util.Collection;
import java.util.List;

public enum IntegrationCapability {
    WORLD_TRACKING("World tracking"),
    MASS_EDIT_GROUPING("Mass edit grouping"),
    OPERATION_TRACKING("Operation tracking"),
    ENTITY_TRACKING("Entity tracking"),
    CUSTOM_REGION_API("Custom region API"),
    FALLBACK_CAPTURE("Fallback capture");

    private final String displayLabel;

    IntegrationCapability(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return this.displayLabel;
    }

    public static List<String> displayLabels(Collection<IntegrationCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        return capabilities.stream()
                .map(IntegrationCapability::displayLabel)
                .toList();
    }
}
