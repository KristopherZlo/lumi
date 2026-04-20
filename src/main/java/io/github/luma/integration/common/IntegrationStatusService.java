package io.github.luma.integration.common;

import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class IntegrationStatusService {

    public List<IntegrationStatus> statuses() {
        return List.of(
                this.status("worldedit", List.of("selection", "operation-tracking", "schematic-bridge")),
                this.status("axiom", List.of("compatibility-tracking", "clipboard-bridge", "schematic-bridge")),
                new IntegrationStatus("fallback", true, List.of("world-tracking", "mass-edit-grouping"), "core")
        );
    }

    private IntegrationStatus status(String modId, List<String> capabilities) {
        boolean available = FabricLoader.getInstance().isModLoaded(modId);
        return new IntegrationStatus(modId, available, capabilities, available ? "detected" : "missing");
    }
}
