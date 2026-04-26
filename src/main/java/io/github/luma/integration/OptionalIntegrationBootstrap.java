package io.github.luma.integration;

import io.github.luma.LumaMod;
import io.github.luma.integration.common.ExternalToolIntegrationRegistry;
import io.github.luma.integration.common.IntegrationCapability;

public final class OptionalIntegrationBootstrap {

    private static final String WORLDEDIT_TRACKER_CLASS = "io.github.luma.integration.worldedit.WorldEditEditSessionTracker";

    private final ExternalToolIntegrationRegistry registry;

    public OptionalIntegrationBootstrap() {
        this(new ExternalToolIntegrationRegistry());
    }

    OptionalIntegrationBootstrap(ExternalToolIntegrationRegistry registry) {
        this.registry = registry;
    }

    public void initialize() {
        if (this.registry.worldEditStatus().capabilities().contains(IntegrationCapability.OPERATION_TRACKING)) {
            this.registerWorldEditTracker();
        }
    }

    private void registerWorldEditTracker() {
        try {
            Class<?> trackerClass = Class.forName(WORLDEDIT_TRACKER_CLASS);
            Object tracker = trackerClass.getDeclaredConstructor().newInstance();
            boolean registered = (boolean) trackerClass.getMethod("register").invoke(tracker);
            if (registered) {
                LumaMod.LOGGER.info("WorldEdit edit-session tracking is active");
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            LumaMod.LOGGER.warn("WorldEdit edit-session tracking is unavailable", exception);
        }
    }
}
