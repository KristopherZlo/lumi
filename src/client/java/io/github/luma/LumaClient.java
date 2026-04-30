package io.github.luma;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.client.command.LumaClientCommands;
import io.github.luma.client.input.KeyBindingState;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.input.UndoRedoKeyChordTracker;
import io.github.luma.client.input.UndoRedoKeyController;
import io.github.luma.client.preview.PreviewCaptureCoordinator;
import io.github.luma.client.selection.LumiRegionSelectionController;
import io.github.luma.debug.StartupProfiler;
import io.github.luma.ui.controller.ClientWorkspaceOpenService;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.overlay.CompareOverlayCoordinator;
import io.github.luma.ui.overlay.RecentChangesOverlayCoordinator;
import io.github.luma.ui.overlay.RecentChangesOverlayRenderer;
import io.github.luma.ui.overlay.LumiRegionSelectionRenderer;
import io.github.luma.ui.overlay.OverlayDiagnostics;
import io.github.luma.ui.overlay.WorkspaceHudCoordinator;
import io.github.luma.ui.screen.QuickSaveScreen;
import org.lwjgl.glfw.GLFW;

public final class LumaClient implements ClientModInitializer {

    private static final long CLASS_LOAD_STARTED_AT = StartupProfiler.start();
    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "general")
    );
    private static final String OPEN_DASHBOARD_KEY = "key.lumi.open_dashboard";
    private static final String QUICK_SAVE_KEY = "key.lumi.quick_save";
    private static final String UNDO_KEY = "key.lumi.undo";
    private static final String REDO_KEY = "key.lumi.redo";
    private static final String TOGGLE_COMPARE_OVERLAY_KEY = "key.lumi.toggle_compare_overlay";
    private static final String COMPARE_OVERLAY_XRAY_KEY = "key.lumi.compare_overlay_xray";

    private KeyMapping openDashboardKey;
    private KeyMapping quickSaveKey;
    private KeyMapping undoKey;
    private KeyMapping redoKey;
    private KeyMapping toggleCompareOverlayKey;
    private KeyMapping lumiActionButtonKey;
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
        this.quickSaveKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                QUICK_SAVE_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_S,
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
        this.lumiActionButtonKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                COMPARE_OVERLAY_XRAY_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                KEY_CATEGORY
        ));
        LumiClientKeyBindings.configure(
                this.openDashboardKey,
                this.quickSaveKey,
                this.undoKey,
                this.redoKey,
                this.toggleCompareOverlayKey,
                this.lumiActionButtonKey
        );
        LumiRegionSelectionController.getInstance().configureActionButton(this.lumiActionButtonKey, this.keyBindingState);
        StartupProfiler.logElapsed("client.key-bindings", keyBindingsStartedAt);

        long eventRegistrationStartedAt = StartupProfiler.start();
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        WorldRenderEvents.END_MAIN.register(CompareOverlayRenderer::render);
        WorldRenderEvents.END_MAIN.register(LumiRegionSelectionRenderer::render);
        WorldRenderEvents.END_MAIN.register(RecentChangesOverlayRenderer::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                new LumaClientCommands(this.workspaceOpenService).register(dispatcher));
        OverlayDiagnostics.getInstance().clientRenderCallbacksRegistered("END_MAIN");
        StartupProfiler.logElapsed("client.fabric-events", eventRegistrationStartedAt);
        long hudStartedAt = StartupProfiler.start();
        WorkspaceHudCoordinator.getInstance().registerHud();
        StartupProfiler.logElapsed("client.hud-registration", hudStartedAt);
        StartupProfiler.logElapsed("client.onInitializeClient", startedAt);
    }

    private void onEndTick(Minecraft client) {
        boolean overlayHold = this.keyBindingState.isDown(client, this.lumiActionButtonKey);
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
                this.lumiActionButtonKey
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

        boolean quickSaveClicked = false;
        while (this.quickSaveKey.consumeClick()) {
            quickSaveClicked = true;
        }
        if (shortcutInputActive && overlayHold && quickSaveClicked) {
            client.setScreen(new QuickSaveScreen());
        }

        boolean openDashboardClicked = false;
        while (this.openDashboardKey.consumeClick()) {
            openDashboardClicked = true;
        }
        if (shortcutInputActive && openDashboardClicked) {
            this.workspaceOpenService.openCurrentWorkspace(client, client.screen);
        }
    }
}
