package io.github.luma.client.update;

import io.github.luma.ui.screen.UpdateAvailableScreen;
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
        if (this.showPromptIfAvailable(client, projectScreen)) {
            this.updateCheckService.requestCheckIfStale();
            return;
        }

        CompletableFuture<UpdateCheckResult> check = this.updateCheckService.requestCheckIfStale();
        check.thenRun(() -> client.execute(() -> {
            if (client.screen == projectScreen) {
                this.showPromptIfAvailable(client, projectScreen);
            }
        }));
    }

    private boolean showPromptIfAvailable(Minecraft client, Screen parent) {
        return this.updateCheckService.promptRelease()
                .map(release -> {
                    client.setScreen(new UpdateAvailableScreen(parent, release, this.updateCheckService));
                    return true;
                })
                .orElse(false);
    }
}
