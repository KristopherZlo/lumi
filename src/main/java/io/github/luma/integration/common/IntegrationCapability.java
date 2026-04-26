package io.github.luma.integration.common;

import java.util.Collection;
import java.util.List;

public enum IntegrationCapability {
    WORLD_TRACKING("world-tracking"),
    MASS_EDIT_GROUPING("mass-edit-grouping"),
    OPERATION_TRACKING("operation-tracking"),
    SELECTION("selection"),
    CLIPBOARD("clipboard"),
    SCHEMATIC("schematic"),
    ENTITY_TRACKING("entity-tracking"),
    CUSTOM_REGION_API("custom-region-api"),
    FALLBACK_CAPTURE("fallback-capture");

    private final String label;

    IntegrationCapability(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public static List<String> labels(Collection<IntegrationCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        return capabilities.stream()
                .map(IntegrationCapability::label)
                .toList();
    }
}
