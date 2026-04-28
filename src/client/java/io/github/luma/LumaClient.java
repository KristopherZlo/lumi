package io.github.luma;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.client.input.UndoRedoKeyChordTracker;
import io.github.luma.client.input.UndoRedoKeyController;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.client.preview.PreviewCaptureCoordinator;
import io.github.luma.ui.controller.ClientProjectAccess;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.overlay.CompareOverlayCoordinator;
import io.github.luma.ui.overlay.RecentChangesOverlayCoordinator;
import io.github.luma.ui.overlay.RecentChangesOverlayRenderer;
import io.github.luma.ui.overlay.WorkspaceHudCoordinator;
import io.github.luma.ui.screen.DashboardScreen;
import io.github.luma.ui.screen.ProjectScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class LumaClient implements ClientModInitializer {

    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "general")
    );
    private static final String OPEN_DASHBOARD_KEY = "key.lumi.open_dashboard";
    private static final String UNDO_KEY = "key.lumi.undo";
    private static final String REDO_KEY = "key.lumi.redo";
    private static final String TOGGLE_COMPARE_OVERLAY_KEY = "key.lumi.toggle_compare_overlay";
    private static final String COMPARE_OVERLAY_XRAY_KEY = "key.lumi.compare_overlay_xray";

    private KeyMapping openDashboardKey;
    private KeyMapping undoKey;
    private KeyMapping redoKey;
    private KeyMapping toggleCompareOverlayKey;
    private KeyMapping compareOverlayXrayKey;
    private final ProjectService projectService = new ProjectService();
    private final UndoRedoKeyChordTracker undoRedoKeyChordTracker = new UndoRedoKeyChordTracker();
    private final UndoRedoKeyController undoRedoKeyController = new UndoRedoKeyController();

    @Override
    public void onInitializeClient() {
        this.openDashboardKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                OPEN_DASHBOARD_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                KEY_CATEGORY
        ));
        this.undoKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                UNDO_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                KEY_CATEGORY
        ));
        this.redoKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                REDO_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                KEY_CATEGORY
        ));
        this.toggleCompareOverlayKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                TOGGLE_COMPARE_OVERLAY_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                KEY_CATEGORY
        ));
        this.compareOverlayXrayKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                COMPARE_OVERLAY_XRAY_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        WorldRenderEvents.END_MAIN.register(CompareOverlayRenderer::render);
        WorldRenderEvents.END_MAIN.register(RecentChangesOverlayRenderer::render);
        WorkspaceHudCoordinator.getInstance().registerHud();
    }

    private void onEndTick(Minecraft client) {
        boolean altHeld = isAltHeld(client);
        WorkspaceHudCoordinator.getInstance().tick(client);
        PreviewCaptureCoordinator.getInstance().tick(client);
        UndoRedoKeyChordTracker.TickResult undoRedoKeys = this.undoRedoKeyChordTracker.tick(
                client,
                altHeld,
                this.undoKey,
                this.redoKey
        );
        CompareOverlayRenderer.setXrayEnabled(this.compareOverlayXrayKey.isDown());
        CompareOverlayCoordinator.getInstance().tick(client);
        RecentChangesOverlayCoordinator.getInstance().tick(client, altHeld, undoRedoKeys.previewTarget());
        while (this.toggleCompareOverlayKey.consumeClick()) {
            CompareOverlayRenderer.toggleVisibility();
        }
        if (undoRedoKeys.undoPressed()) {
            this.undoRedoKeyController.undo(client);
        }
        if (undoRedoKeys.redoPressed()) {
            this.undoRedoKeyController.redo(client);
        }

        while (this.openDashboardKey.consumeClick()) {
            if (client.player == null) {
                continue;
            }

            try {
                var server = ClientProjectAccess.requireSingleplayerServer(client);
                var level = server.getLevel(client.level == null ? net.minecraft.world.level.Level.OVERWORLD : client.level.dimension());
                if (level == null) {
                    level = server.overworld();
                }

                String projectName = this.projectService.ensureWorldProject(level, client.getUser().getName()).name();
                client.setScreen(new ProjectScreen(client.screen, projectName));
            } catch (IllegalStateException exception) {
                client.gui.setOverlayMessage(Component.translatable("luma.status.admin_required"), false);
            } catch (Exception exception) {
                client.setScreen(new DashboardScreen(client.screen));
            }
        }
    }

    private static boolean isAltHeld(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        var window = client.getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }
}
