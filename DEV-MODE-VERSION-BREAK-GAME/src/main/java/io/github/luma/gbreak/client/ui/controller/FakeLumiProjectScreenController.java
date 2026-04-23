package io.github.luma.gbreak.client.ui.controller;

import io.github.luma.gbreak.client.ui.state.FakeLumiProjectViewState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

public final class FakeLumiProjectScreenController {

    private final FakeLumiProjectCatalog catalog;

    public FakeLumiProjectScreenController() {
        this(new FakeLumiProjectCatalog());
    }

    public FakeLumiProjectScreenController(FakeLumiProjectCatalog catalog) {
        this.catalog = catalog;
    }

    public FakeLumiProjectViewState loadState(MinecraftClient client) {
        return new FakeLumiProjectViewState(
                "DEV-MODE-VERSION-BREAK-GAME",
                this.dimensionName(client == null ? null : client.world),
                "corrupt-preview",
                "Project loaded. Restore, compare, and history tools are available.",
                14,
                3,
                9,
                this.catalog.defaultVariants(),
                this.catalog.defaultCommits()
        );
    }

    private String dimensionName(ClientWorld world) {
        if (world == null) {
            return "menu";
        }

        String path = world.getRegistryKey().getValue().getPath();
        return switch (path) {
            case "overworld" -> "Overworld";
            case "the_nether" -> "The Nether";
            case "the_end" -> "The End";
            default -> path.replace('_', ' ');
        };
    }
}
