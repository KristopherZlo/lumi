package io.github.luma.integration.common;

import java.util.List;

public record IntegrationStatus(
        String toolId,
        boolean available,
        List<IntegrationCapability> capabilities,
        IntegrationMode mode
) {

    public IntegrationStatus {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        mode = mode == null ? IntegrationMode.UNAVAILABLE : mode;
    }

    public List<String> capabilityLabels() {
        return IntegrationCapability.labels(this.capabilities);
    }

    public String modeLabel() {
        return this.mode.label();
    }
}
