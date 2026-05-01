package io.github.luma.integration.common;

import java.util.Collection;
import java.util.List;

public enum IntegrationCapability {
    WORLD_TRACKING("world-tracking", "World tracking"),
    MASS_EDIT_GROUPING("mass-edit-grouping", "Mass edit grouping"),
    OPERATION_TRACKING("operation-tracking", "Operation tracking"),
    SELECTION("selection", "Stable selection"),
    CLIPBOARD("clipboard", "Stable clipboard"),
    SCHEMATIC("schematic", "Stable schematic formats"),
    ENTITY_TRACKING("entity-tracking", "Entity tracking"),
    CUSTOM_REGION_API("custom-region-api", "Custom region API"),
    FALLBACK_CAPTURE("fallback-capture", "Fallback capture");

    private final String label;
    private final String displayLabel;

    IntegrationCapability(String label, String displayLabel) {
        this.label = label;
        this.displayLabel = displayLabel;
    }

    public String label() {
        return this.label;
    }

    public String displayLabel() {
        return this.displayLabel;
    }

    public static List<String> labels(Collection<IntegrationCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        return capabilities.stream()
                .map(IntegrationCapability::label)
                .toList();
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
