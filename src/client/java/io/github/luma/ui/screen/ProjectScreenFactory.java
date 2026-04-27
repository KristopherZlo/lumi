package io.github.luma.ui.screen;

import net.minecraft.client.gui.screens.Screen;

public final class ProjectScreenFactory {

    private ProjectScreenFactory() {
    }

    public static Screen create(Screen parent, String projectName) {
        return create(parent, projectName, "", "luma.status.project_ready");
    }

    public static Screen create(Screen parent, String projectName, String statusKey) {
        return create(parent, projectName, "", statusKey);
    }

    public static Screen create(Screen parent, String projectName, String selectedVariantId, String statusKey) {
        return LdLib2ProjectHomeScreenFactory.create(parent, projectName, selectedVariantId, statusKey)
                .orElseGet(() -> new ProjectScreen(parent, projectName, selectedVariantId, statusKey));
    }
}
