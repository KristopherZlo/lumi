package io.github.luma.client.update;

import io.github.luma.ui.screen.ProjectScreen;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class UpdatePromptCoordinator {

    private final UpdateCheckService updateCheckService;

    public UpdatePromptCoordinator() {
        this(UpdateCheckService.getInstance());
    }

    UpdatePromptCoordinator(UpdateCheckService updateCheckService) {
        this.updateCheckService = updateCheckService;
    }

    public void openProjectScreen(Minecraft client, Screen projectScreen) {
        if (client == null || projectScreen == null) {
            return;
        }

        client.setScreen(projectScreen);
        CompletableFuture<UpdateCheckResult> check = this.updateCheckService.requestCheckIfStale();
        check.thenRun(() -> client.execute(() -> {
            if (client.screen == projectScreen && projectScreen instanceof ProjectScreen screen) {
                screen.refreshUpdateNotice();
            }
        }));
    }
}
