package io.github.luma.integration.common;

import java.util.List;

public final class IntegrationStatusService {

    private final ExternalToolIntegrationRegistry registry;

    public IntegrationStatusService() {
        this(new ExternalToolIntegrationRegistry());
    }

    public IntegrationStatusService(ExternalToolIntegrationRegistry registry) {
        this.registry = registry;
    }

    public List<IntegrationStatus> statuses() {
        return this.registry.statuses();
    }
}
