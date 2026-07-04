package io.github.luma;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.client.command.LumaClientCommands;
import io.github.luma.client.diagnostics.ClientRuntimeLoadSampler;
import io.github.luma.client.input.KeyBindingState;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.input.LumiShortcutInteractionGate;
import io.github.luma.client.input.LumiShortcutScreenPolicy;
import io.github.luma.client.input.LumiShortcutSuppressingScreen;
import io.github.luma.client.input.QuickRollbackKeyController;
import io.github.luma.client.input.UndoRedoKeyChordTracker;
import io.github.luma.client.input.UndoRedoKeyController;
import io.github.luma.client.onboarding.ClientOnboardingFlowCoordinator;
import io.github.luma.client.telemetry.TelemetryNoticeController;
import io.github.luma.client.preview.PreviewCaptureCoordinator;
import io.github.luma.client.selection.LumiRegionSelectionController;
import io.github.luma.client.selection.LumiRegionSelectionTeachingController;
import io.github.luma.client.specialthanks.SpecialThanksClientCache;
import io.github.luma.client.update.MinecraftUpdateNoticeSink;
import io.github.luma.client.update.UpdateWorldJoinNotifier;
import io.github.luma.debug.StartupProfiler;
import io.github.luma.debug.TesterDiagnosticsMode;
import io.github.luma.network.WorkZoneClientNetworking;
import io.github.luma.telemetry.TelemetryService;
import io.github.luma.ui.controller.AsyncCompareCache;
import io.github.luma.ui.controller.ClientWorkspaceOpenService;
import io.github.luma.ui.preview.ProjectPreviewTextureCache;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.overlay.CompareOverlayHotkeyHud;
import io.github.luma.ui.overlay.CompareOverlayCoordinator;
import io.github.luma.ui.overlay.PendingChangesOverlayCoordinator;
import io.github.luma.ui.overlay.PendingChangesOverlayRenderer;
import io.github.luma.ui.overlay.RecentChangesOverlayCoordinator;
import io.github.luma.ui.overlay.RecentChangesOverlayRenderer;
import io.github.luma.ui.overlay.WorkZoneOverlayRenderer;
import io.github.luma.ui.overlay.LumiRegionSelectionRenderer;
import io.github.luma.ui.overlay.OverlayDiagnostics;
import io.github.luma.ui.overlay.WorkspaceHudCoordinator;
import io.github.luma.ui.screen.HotkeyInfoScreen;
import io.github.luma.ui.screen.ProjectScreen;
import io.github.luma.ui.screen.QuickSaveScreen;
import io.github.luma.ui.screen.WorkZoneScreen;
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
    private static final String QUICK_ROLLBACK_KEY = "key.lumi.quick_rollback";
    private static final String COMPARE_OVERLAY_XRAY_KEY = "key.lumi.compare_overlay_xray";
    private static final String HOTKEY_INFO_KEY = "key.lumi.hotkey_info";

    private KeyMapping openDashboardKey;
    private KeyMapping quickSaveKey;
    private KeyMapping undoKey;
    private KeyMapping redoKey;
    private KeyMapping toggleCompareOverlayKey;
    private KeyMapping quickRollbackKey;
    private KeyMapping lumiActionButtonKey;
    private KeyMapping hotkeyInfoKey;
    private final KeyBindingState keyBindingState = new KeyBindingState();
    private final UndoRedoKeyChordTracker undoRedoKeyChordTracker = new UndoRedoKeyChordTracker();
    private final UndoRedoKeyController undoRedoKeyController = new UndoRedoKeyController();
    private final QuickRollbackKeyController quickRollbackKeyController = new QuickRollbackKeyController();
    private final LumiShortcutScreenPolicy shortcutScreenPolicy = new LumiShortcutScreenPolicy();
    private final LumiRegionSelectionTeachingController selectionTeachingController = new LumiRegionSelectionTeachingController();
    private final ClientWorkspaceOpenService workspaceOpenService = new ClientWorkspaceOpenService();
    private final UpdateWorldJoinNotifier updateWorldJoinNotifier = new UpdateWorldJoinNotifier();
    private final TelemetryNoticeController telemetryNoticeController = new TelemetryNoticeController();
    private final boolean clientRuntimeLoadSamplingEnabled = this.configureClientRuntimeLoadSampling();
    private boolean worldActive;

    static {
        StartupProfiler.logElapsed("client.class-load", CLASS_LOAD_STARTED_AT);
    }

    @Override
    public void onInitializeClient() {
        long startedAt = StartupProfiler.start();
        TelemetryService.getInstance().enableClientRuntime();
        this.installCrashHandler();
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
        this.quickRollbackKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                QUICK_ROLLBACK_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KEY_CATEGORY
        ));
        this.lumiActionButtonKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                COMPARE_OVERLAY_XRAY_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                KEY_CATEGORY
        ));
        this.hotkeyInfoKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                HOTKEY_INFO_KEY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                KEY_CATEGORY
        ));
        LumiClientKeyBindings.configure(
                this.openDashboardKey,
                this.quickSaveKey,
                this.undoKey,
                this.redoKey,
                this.toggleCompareOverlayKey,
                this.quickRollbackKey,
                this.lumiActionButtonKey,
                this.hotkeyInfoKey
        );
        LumiShortcutInteractionGate.getInstance().configure(
                this.lumiActionButtonKey,
                this.undoKey,
                this.redoKey,
                this.keyBindingState
        );
        LumiRegionSelectionController.getInstance().configureActionButton(this.lumiActionButtonKey, this.keyBindingState);
        StartupProfiler.logElapsed("client.key-bindings", keyBindingsStartedAt);

        long eventRegistrationStartedAt = StartupProfiler.start();
        WorkZoneClientNetworking.getInstance().register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        if (this.clientRuntimeLoadSamplingEnabled) {
            WorldRenderEvents.END_MAIN.register(ClientRuntimeLoadSampler.getInstance()::onWorldRender);
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientRuntimeLoadSampler.getInstance().close());
        }
        WorldRenderEvents.END_MAIN.register(CompareOverlayRenderer::render);
        WorldRenderEvents.END_MAIN.register(LumiRegionSelectionRenderer::render);
        WorldRenderEvents.END_MAIN.register(WorkZoneOverlayRenderer::render);
        WorldRenderEvents.END_MAIN.register(PendingChangesOverlayRenderer::render);
        WorldRenderEvents.END_MAIN.register(RecentChangesOverlayRenderer::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                new LumaClientCommands(this.workspaceOpenService).register(dispatcher));
        OverlayDiagnostics.getInstance().clientRenderCallbacksRegistered("END_MAIN");
        StartupProfiler.logElapsed("client.fabric-events", eventRegistrationStartedAt);
        this.updateWorldJoinNotifier.requestStartupCheck();
        SpecialThanksClientCache.getInstance().preload(Minecraft.getInstance());
        long hudStartedAt = StartupProfiler.start();
        WorkspaceHudCoordinator.getInstance().registerHud();
        CompareOverlayHotkeyHud.registerHud();
        this.selectionTeachingController.registerHud();
        ClientOnboardingFlowCoordinator.getInstance().registerHud();
        StartupProfiler.logElapsed("client.hud-registration", hudStartedAt);
        StartupProfiler.logElapsed("client.onInitializeClient", startedAt);
    }

    private void onEndTick(Minecraft client) {
        if (this.clientRuntimeLoadSamplingEnabled) {
            ClientRuntimeLoadSampler.getInstance().tick(client);
        }
        boolean activeWorldNow = client != null && client.level != null;
        if (this.worldActive && !activeWorldNow) {
            this.clearWorldClientState();
        }
        if (!this.worldActive && activeWorldNow) {
            this.updateWorldJoinNotifier.notifyAfterWorldJoin(new MinecraftUpdateNoticeSink(client));
            this.showTelemetryNotice(client);
        }
        this.worldActive = activeWorldNow;

        boolean shortcutsSuppressed = this.lumiShortcutsSuppressed(client);
        boolean overlayHold = !shortcutsSuppressed && this.keyBindingState.isDown(client, this.lumiActionButtonKey);
        boolean worldInputActive = this.shortcutScreenPolicy.worldInputActive(client, shortcutsSuppressed);
        boolean undoRedoInputActive = this.shortcutScreenPolicy.undoRedoInputActive(client, shortcutsSuppressed);
        WorkspaceHudCoordinator.getInstance().tick(client);
        WorkZoneOverlayRenderer.tick(client);
        ClientOnboardingFlowCoordinator.getInstance().tick(client);
        PreviewCaptureCoordinator.getInstance().tick(client);
        this.selectionTeachingController.tick(client);
        if (shortcutsSuppressed) {
            this.drainLumiShortcutClicks();
            UndoRedoKeyChordTracker.TickResult idleKeys = this.undoRedoKeyChordTracker.tick(
                    client,
                    false,
                    false,
                    null,
                    null
            );
            LumiShortcutInteractionGate.getInstance().tick(false, idleKeys);
            CompareOverlayRenderer.setXrayEnabled(false);
            PendingChangesOverlayCoordinator.getInstance().tick(client, false);
            RecentChangesOverlayCoordinator.getInstance().tick(client, false, idleKeys.previewTarget());
            OverlayDiagnostics.getInstance().clientTick(
                    client,
                    false,
                    false,
                    idleKeys.previewTarget(),
                    false,
                    false,
                    this.lumiActionButtonKey
            );
            return;
        }
        UndoRedoKeyChordTracker.TickResult undoRedoKeys = this.undoRedoKeyChordTracker.tick(
                client,
                undoRedoInputActive,
                overlayHold,
                this.undoKey,
                this.redoKey
        );
        RecentChangesOverlayCoordinator.PreviewTarget recentPreviewTarget = overlayHold
                ? RecentChangesOverlayCoordinator.PreviewTarget.BOTH
                : undoRedoKeys.previewTarget();
        LumiShortcutInteractionGate.getInstance().tick(worldInputActive, undoRedoKeys);
        CompareOverlayRenderer.setXrayEnabled(overlayHold);
        CompareOverlayCoordinator.getInstance().tick(client);
        boolean recentPreviewActive = RecentChangesOverlayCoordinator.getInstance().tick(
                client,
                worldInputActive && overlayHold,
                recentPreviewTarget
        );
        PendingChangesOverlayCoordinator.getInstance().tick(
                client,
                worldInputActive && overlayHold && !recentPreviewActive
        );
        OverlayDiagnostics.getInstance().clientTick(
                client,
                overlayHold,
                undoRedoInputActive,
                recentPreviewTarget,
                undoRedoKeys.undoPressed(),
                undoRedoKeys.redoPressed(),
                this.lumiActionButtonKey
        );
        while (this.toggleCompareOverlayKey.consumeClick()) {
            CompareOverlayRenderer.toggleVisibility();
        }
        if (undoRedoKeys.undoPressed()) {
            if (!LumiRegionSelectionController.getInstance().handleUndoRedo(client, true)) {
                this.undoRedoKeyController.undo(client);
            }
        }
        if (undoRedoKeys.redoPressed()) {
            if (!LumiRegionSelectionController.getInstance().handleUndoRedo(client, false)) {
                this.undoRedoKeyController.redo(client);
            }
        }
        this.undoRedoKeyController.tick(client);

        boolean quickRollbackClicked = false;
        while (this.quickRollbackKey.consumeClick()) {
            quickRollbackClicked = true;
        }
        if (worldInputActive && quickRollbackClicked && !overlayHold) {
            this.quickRollbackKeyController.quickRollback(client);
        }

        boolean quickSaveClicked = false;
        while (this.quickSaveKey.consumeClick()) {
            quickSaveClicked = true;
        }
        boolean projectScreenQuickSave = client.screen instanceof ProjectScreen;
        boolean workZoneScreenQuickSave = client.screen instanceof WorkZoneScreen;
        if ((worldInputActive || projectScreenQuickSave || workZoneScreenQuickSave) && overlayHold && quickSaveClicked) {
            if (worldInputActive && this.workspaceOpenService.rejectIfSurvivalDisabled(client)) {
                return;
            }
            if (client.screen instanceof ProjectScreen projectScreen) {
                projectScreen.openSaveDialog();
            } else if (client.screen instanceof WorkZoneScreen workZoneScreen) {
                workZoneScreen.openZoneSaveDialog();
            } else {
                client.setScreen(new QuickSaveScreen());
            }
        }

        boolean hotkeyInfoClicked = false;
        while (this.hotkeyInfoKey.consumeClick()) {
            hotkeyInfoClicked = true;
        }
        if (worldInputActive && overlayHold && hotkeyInfoClicked) {
            if (this.workspaceOpenService.rejectIfSurvivalDisabled(client)) {
                return;
            }
            client.setScreen(new HotkeyInfoScreen(null));
            return;
        }

        boolean openDashboardClicked = false;
        while (this.openDashboardKey.consumeClick()) {
            openDashboardClicked = true;
        }
        if (worldInputActive && openDashboardClicked) {
            this.workspaceOpenService.openCurrentWorkspace(client, client.screen);
        }
    }

    private void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            TelemetryService.getInstance().recordClientCrashCandidate(throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private boolean configureClientRuntimeLoadSampling() {
        TesterDiagnosticsMode.applyDefaults();
        return ClientRuntimeLoadSampler.configuredEnabled();
    }

    private void showTelemetryNotice(Minecraft client) {
        if (client == null || client.gui == null || !this.telemetryNoticeController.shouldShowNotice()) {
            return;
        }
        client.gui.setOverlayMessage(Component.translatable("luma.telemetry.notice"), false);
        this.telemetryNoticeController.acknowledgeNotice();
    }

    private void clearWorldClientState() {
        CompareOverlayRenderer.clear();
        PendingChangesOverlayRenderer.clear();
        RecentChangesOverlayRenderer.clear();
        AsyncCompareCache.getInstance().clear();
        ProjectPreviewTextureCache.releaseAll();
    }

    private boolean lumiShortcutsSuppressed(Minecraft client) {
        if (client == null) {
            return false;
        }
        if (client.screen instanceof PauseScreen) {
            return true;
        }
        if (ClientOnboardingFlowCoordinator.getInstance().suppressesLumiShortcuts()) {
            return true;
        }
        return client.screen instanceof LumiShortcutSuppressingScreen suppressingScreen
                && suppressingScreen.suppressesLumiShortcuts();
    }

    private void drainLumiShortcutClicks() {
        this.drainClicks(this.openDashboardKey);
        this.drainClicks(this.quickSaveKey);
        this.drainClicks(this.undoKey);
        this.drainClicks(this.redoKey);
        this.drainClicks(this.toggleCompareOverlayKey);
        this.drainClicks(this.quickRollbackKey);
        this.drainClicks(this.lumiActionButtonKey);
        this.drainClicks(this.hotkeyInfoKey);
    }

    private void drainClicks(KeyMapping key) {
        if (key == null) {
            return;
        }
        while (key.consumeClick()) {
            // Drain queued key presses so onboarding can observe held keys
            // without executing their normal in-world shortcut action.
        }
    }
}
