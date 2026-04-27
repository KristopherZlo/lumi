package io.github.luma.ui.toolkit;

import java.util.List;

public record LdLib2ElementRole(
        String id,
        String ldLib2Type,
        String purpose,
        List<String> classes,
        List<LdLib2LayoutRule> layoutRules
) {

    public LdLib2ElementRole(String id, String ldLib2Type, String purpose) {
        this(id, ldLib2Type, purpose, List.of(), List.of());
    }

    public LdLib2ElementRole {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Element id is required.");
        }
        if (ldLib2Type == null || ldLib2Type.isBlank()) {
            throw new IllegalArgumentException("LDLib2 element type is required.");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Element purpose is required.");
        }
        classes = List.copyOf(classes == null ? List.of() : classes);
        layoutRules = List.copyOf(layoutRules == null ? List.of() : layoutRules);
        if (classes.stream().anyMatch(cssClass -> cssClass == null || cssClass.isBlank())) {
            throw new IllegalArgumentException("LDLib2 element class names must be non-empty.");
        }
        if (layoutRules.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("LDLib2 layout rules must be non-null.");
        }
    }

    public boolean hasClass(String className) {
        return this.classes.contains(className);
    }

    public boolean hasLayoutRule(String property, String value) {
        return this.layoutRules.stream()
                .anyMatch(rule -> rule.property().equals(property) && rule.value().equals(value));
    }
}
