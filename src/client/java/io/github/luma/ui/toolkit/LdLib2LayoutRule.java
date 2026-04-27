package io.github.luma.ui.toolkit;

public record LdLib2LayoutRule(String property, String value) {

    public LdLib2LayoutRule {
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("LDLib2 layout property is required.");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LDLib2 layout value is required.");
        }
    }

    public static LdLib2LayoutRule of(String property, String value) {
        return new LdLib2LayoutRule(property, value);
    }
}
