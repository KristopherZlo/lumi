package io.github.luma.integration.common;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalToolIntegrationRegistryTest {

    @Test
    void reportsFallbackCaptureWithoutExternalMods() {
        ExternalToolIntegrationRegistry registry = new ExternalToolIntegrationRegistry(
                modId -> false,
                className -> false
        );

        IntegrationStatus worldEdit = registry.worldEditStatus();
        IntegrationStatus fawe = registry.faweStatus();
        IntegrationStatus axiom = registry.axiomStatus();
        IntegrationStatus fallback = registry.fallbackStatus();

        assertFalse(worldEdit.available());
        assertEquals(IntegrationMode.UNAVAILABLE, worldEdit.mode());
        assertFalse(fawe.available());
        assertEquals(IntegrationMode.UNAVAILABLE, fawe.mode());
        assertFalse(axiom.available());
        assertEquals(IntegrationMode.UNAVAILABLE, axiom.mode());
        assertTrue(fallback.available());
        assertEquals(IntegrationMode.FALLBACK, fallback.mode());
        assertTrue(fallback.capabilities().contains(IntegrationCapability.WORLD_TRACKING));
        assertTrue(fallback.capabilities().contains(IntegrationCapability.MASS_EDIT_GROUPING));
        assertTrue(fallback.capabilities().contains(IntegrationCapability.FALLBACK_CAPTURE));
    }

    @Test
    void reportsFaweAsDetectedFallbackCapture() {
        Set<String> classes = Set.of("com.fastasyncworldedit.core.Fawe");
        ExternalToolIntegrationRegistry registry = new ExternalToolIntegrationRegistry(
                modId -> false,
                classes::contains
        );

        IntegrationStatus status = registry.faweStatus();

        assertTrue(status.available());
        assertEquals(IntegrationMode.DETECTED, status.mode());
        assertTrue(status.capabilities().contains(IntegrationCapability.FALLBACK_CAPTURE));
        assertTrue(status.capabilities().contains(IntegrationCapability.MASS_EDIT_GROUPING));
        assertFalse(status.capabilities().contains(IntegrationCapability.SELECTION));
        assertFalse(status.capabilities().contains(IntegrationCapability.CLIPBOARD));
    }

    @Test
    void reportsWorldEditCapabilitiesOnlyWhenApiClassesArePresent() {
        Set<String> classes = Set.of(
                "com.sk89q.worldedit.WorldEdit",
                "com.sk89q.worldedit.event.extent.EditSessionEvent",
                "com.sk89q.worldedit.LocalSession",
                "com.sk89q.worldedit.extent.clipboard.Clipboard",
                "com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats"
        );
        ExternalToolIntegrationRegistry registry = new ExternalToolIntegrationRegistry(
                "worldedit"::equals,
                classes::contains
        );

        IntegrationStatus status = registry.worldEditStatus();

        assertTrue(status.available());
        assertEquals(IntegrationMode.ACTIVE, status.mode());
        assertTrue(status.capabilities().contains(IntegrationCapability.OPERATION_TRACKING));
        assertTrue(status.capabilities().contains(IntegrationCapability.MASS_EDIT_GROUPING));
        assertTrue(status.capabilities().contains(IntegrationCapability.SELECTION));
        assertTrue(status.capabilities().contains(IntegrationCapability.CLIPBOARD));
        assertTrue(status.capabilities().contains(IntegrationCapability.SCHEMATIC));
    }

    @Test
    void reportsAxiomAsPartialWithoutSelectionOrClipboardPromises() {
        Set<String> classes = Set.of("com.moulberry.axiomclientapi.service.ToolRegistryService");
        ExternalToolIntegrationRegistry registry = new ExternalToolIntegrationRegistry(
                "axiom"::equals,
                classes::contains
        );

        IntegrationStatus status = registry.axiomStatus();

        assertTrue(status.available());
        assertEquals(IntegrationMode.PARTIAL, status.mode());
        assertTrue(status.capabilities().contains(IntegrationCapability.CUSTOM_REGION_API));
        assertFalse(status.capabilities().contains(IntegrationCapability.SELECTION));
        assertFalse(status.capabilities().contains(IntegrationCapability.CLIPBOARD));
        assertFalse(status.capabilities().contains(IntegrationCapability.SCHEMATIC));
    }
}
