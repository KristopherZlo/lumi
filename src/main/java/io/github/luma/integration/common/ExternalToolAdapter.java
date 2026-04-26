package io.github.luma.integration.common;

import java.util.List;
import java.util.Set;

public interface ExternalToolAdapter {

    String toolId();

    boolean available();

    Set<IntegrationCapability> capabilities();

    default IntegrationStatus status() {
        return new IntegrationStatus(
                this.toolId(),
                this.available(),
                List.copyOf(this.capabilities()),
                this.available() ? IntegrationMode.ACTIVE : IntegrationMode.UNAVAILABLE
        );
    }
}
