package io.github.luma.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVariantSwitchKeys;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import io.github.luma.ui.controller.ProjectScreenController;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class BranchSwitchHotkeyController {

    private static final BranchSwitchHotkeyController INSTANCE = new BranchSwitchHotkeyController();

    private final ProjectService projectService = new ProjectService();
    private final ProjectScreenController actionController = new ProjectScreenController();
    private final KeyBindingState keyBindingState = new KeyBindingState();

    private BranchSwitchHotkeyController() {
    }

    public static BranchSwitchHotkeyController getInstance() {
        return INSTANCE;
    }

    public boolean handleKeyPress(Minecraft client, int action, KeyEvent event) {
        if (!this.canHandle(client, action, event)) {
            return false;
        }

        String switchKey = InputConstants.getKey(event).getName();
        try {
            Optional<BuildProject> project = ClientProjectAccess.findCurrentWorldProject(client, this.projectService);
            if (project.isEmpty()) {
                return false;
            }
            List<ProjectVariant> variants = this.projectService.loadVariants(
                    ClientProjectAccess.requireSingleplayerServer(client),
                    project.get().name()
            );
            ProjectVariant target = variants.stream()
                    .filter(variant -> ProjectVariantSwitchKeys.normalize(switchKey).equals(ProjectVariantSwitchKeys.normalize(variant.switchKey())))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return false;
            }
            if (target.id().equals(project.get().activeVariantId())) {
                return true;
            }

            String status = this.actionController.switchVariant(project.get().name(), target.id());
            this.showStatus(client, status);
            return true;
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Branch switch hotkey failed for key {}", switchKey, exception);
            this.showStatus(client, "luma.status.operation_failed");
            return true;
        }
    }

    private boolean canHandle(Minecraft client, int action, KeyEvent event) {
        var actionKey = LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION);
        return client != null
                && client.screen == null
                && client.player != null
                && client.level != null
                && event != null
                && action == GLFW.GLFW_PRESS
                && event.key() != GLFW.GLFW_KEY_UNKNOWN
                && !this.sameKey(event, actionKey)
                && this.keyBindingState.isDown(client, actionKey);
    }

    private boolean sameKey(KeyEvent event, net.minecraft.client.KeyMapping key) {
        if (event == null || key == null || key.isUnbound()) {
            return false;
        }
        try {
            InputConstants.Key boundKey = InputConstants.getKey(key.saveString());
            return boundKey.getType() == InputConstants.Type.KEYSYM && boundKey.getValue() == event.key();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void showStatus(Minecraft client, String status) {
        if (client == null || client.gui == null) {
            return;
        }
        Component message = "luma.status.variant_switched".equals(status)
                ? ActionBarMessagePresenter.success(status)
                : ActionBarMessagePresenter.warning(status);
        client.gui.setOverlayMessage(message, false);
    }
}
