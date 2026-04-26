package io.github.luma.integration.common;

public enum IntegrationMode {
    ACTIVE("active"),
    DETECTED("detected"),
    PARTIAL("partial"),
    FALLBACK("fallback"),
    UNAVAILABLE("missing");

    private final String label;

    IntegrationMode(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }
}
