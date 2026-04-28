package io.github.luma;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.client.input.KeyBindingState;
import io.github.luma.client.input.UndoRedoKeyChordTracker;
import io.github.luma.client.input.UndoRedoKeyController;
import io.github.luma.client.preview.PreviewCaptureCoordinator;
import io.github.luma.debug.StartupProfiler;
import io.github.luma.ui.controller.ClientWorkspaceOpenService;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.overlay.CompareOverlayCoordinator;
import io.github.luma.ui.overlay.RecentChangesOverlayCoordinator;
import io.github.luma.ui.overlay.RecentChangesOverlayRenderer;
import io.github.luma.ui.overlay.OverlayDiagnostics;
import io.github.luma.ui.overlay.WorkspaceHudCoordinator;
import org.lwjgl.glfw.GLFW;

public final class LumaClient implements ClientModInitializer {

    private static final long CLASS_LOAD_STARTED_AT = StartupProfiler.start();
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
    private final KeyBindingState keyBindingState = new KeyBindingState();
    private final UndoRedoKeyChordTracker undoRedoKeyChordTracker = new UndoRedoKeyChordTracker();
    private final UndoRedoKeyController undoRedoKeyController = new UndoRedoKeyController();
    private final ClientWorkspaceOpenService workspaceOpenService = new ClientWorkspaceOpenService();

    static {
        StartupProfiler.logElapsed("client.class-load", CLASS_LOAD_STARTED_AT);
    }

    @Override
    public void onInitializeClient() {
        long startedAt = StartupProfiler.start();
        long keyBindingsStartedAt = StartupProfiler.start();
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
        StartupProfiler.logElapsed("client.key-bindings", keyBindingsStartedAt);

        long eventRegistrationStartedAt = StartupProfiler.start();
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(CompareOverlayRenderer::render);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(RecentChangesOverlayRenderer::render);
        OverlayDiagnostics.getInstance().clientRenderCallbacksRegistered("BEFORE_DEBUG_RENDER");
        StartupProfiler.logElapsed("client.fabric-events", eventRegistrationStartedAt);
        long hudStartedAt = StartupProfiler.start();
        WorkspaceHudCoordinator.getInstance().registerHud();
        StartupProfiler.logElapsed("client.hud-registration", hudStartedAt);
        StartupProfiler.logElapsed("client.onInitializeClient", startedAt);
    }

    private void onEndTick(Minecraft client) {
        boolean overlayHold = this.keyBindingState.isDown(client, this.compareOverlayXrayKey);
        boolean shortcutInputActive = client != null
                && client.screen == null
                && client.player != null
                && client.level != null;
        WorkspaceHudCoordinator.getInstance().tick(client);
        PreviewCaptureCoordinator.getInstance().tick(client);
        UndoRedoKeyChordTracker.TickResult undoRedoKeys = this.undoRedoKeyChordTracker.tick(
                client,
                shortcutInputActive,
                overlayHold,
                this.undoKey,
                this.redoKey
        );
        CompareOverlayRenderer.setXrayEnabled(overlayHold);
        CompareOverlayCoordinator.getInstance().tick(client);
        RecentChangesOverlayCoordinator.getInstance().tick(
                client,
                shortcutInputActive && overlayHold,
                undoRedoKeys.previewTarget()
        );
        OverlayDiagnostics.getInstance().clientTick(
                client,
                overlayHold,
                shortcutInputActive,
                undoRedoKeys.previewTarget(),
                undoRedoKeys.undoPressed(),
                undoRedoKeys.redoPressed(),
                this.compareOverlayXrayKey
        );
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
            this.workspaceOpenService.openCurrentWorkspace(client, client.screen);
        }
    }
}
