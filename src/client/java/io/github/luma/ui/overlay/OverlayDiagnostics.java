package io.github.luma.ui.overlay;

import io.github.luma.debug.LumaDebugLog;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Keeps high-frequency overlay diagnostics useful without logging every frame.
 */
public final class OverlayDiagnostics {

    private static final int FRAME_LOG_INTERVAL = 60;
    private static final OverlayDiagnostics INSTANCE = new OverlayDiagnostics();

    private final Map<String, Integer> cooldowns = new HashMap<>();
    private boolean lastOverlayHold;
    private boolean lastShortcutInputActive;
    private boolean lastCompareVisible;
    private boolean lastRecentVisible;
    private RecentChangesOverlayCoordinator.PreviewTarget lastPreviewTarget =
            RecentChangesOverlayCoordinator.PreviewTarget.UNDO;

    private OverlayDiagnostics() {
    }

    public static OverlayDiagnostics getInstance() {
        return INSTANCE;
    }

    public void clientRenderCallbacksRegistered(String stage) {
        LumaDebugLog.log("overlay-render", "Registered compare and recent overlay callbacks at {}", stage);
    }

    public void clientTick(
            Minecraft client,
            boolean overlayHold,
            boolean shortcutInputActive,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
            boolean undoPressed,
            boolean redoPressed,
            KeyMapping overlayKey
    ) {
        boolean compareVisible = CompareOverlayRenderer.visible();
        boolean recentVisible = RecentChangesOverlayRenderer.visible();
        boolean changed = overlayHold != this.lastOverlayHold
                || shortcutInputActive != this.lastShortcutInputActive
                || compareVisible != this.lastCompareVisible
                || recentVisible != this.lastRecentVisible
                || previewTarget != this.lastPreviewTarget;

        this.lastOverlayHold = overlayHold;
        this.lastShortcutInputActive = shortcutInputActive;
        this.lastCompareVisible = compareVisible;
        this.lastRecentVisible = recentVisible;
        this.lastPreviewTarget = previewTarget;

        if (!changed && !overlayHold && !undoPressed && !redoPressed) {
            return;
        }
        if (!this.shouldLog(false, "client-tick", changed || undoPressed || redoPressed)) {
            return;
        }

        LumaDebugLog.log(
                "overlay-input",
                "tick hold={} shortcutActive={} preview={} undoPressed={} redoPressed={} compareVisible={} recentVisible={} screen={} player={} level={} key={}",
                overlayHold,
                shortcutInputActive,
                previewTarget,
                undoPressed,
                redoPressed,
                compareVisible,
                recentVisible,
                screenName(client),
                client != null && client.player != null,
                client != null && client.level != null,
                keyName(overlayKey)
        );
    }

    public void logNow(boolean debugEnabled, String category, String message, Object... arguments) {
        LumaDebugLog.log(debugEnabled, category, message, arguments);
    }

    public void log(boolean debugEnabled, String key, String category, String message, Object... arguments) {
        if (!this.shouldLog(debugEnabled, key, false)) {
            return;
        }
        LumaDebugLog.log(debugEnabled, category, message, arguments);
    }

    private boolean shouldLog(boolean debugEnabled, String key, boolean immediate) {
        if (!debugEnabled && !LumaDebugLog.globalEnabled()) {
            return false;
        }
        if (immediate) {
            this.cooldowns.put(key, FRAME_LOG_INTERVAL);
            return true;
        }

        int remaining = this.cooldowns.getOrDefault(key, 0);
        if (remaining > 0) {
            this.cooldowns.put(key, remaining - 1);
            return false;
        }

        this.cooldowns.put(key, FRAME_LOG_INTERVAL);
        return true;
    }

    private static String screenName(Minecraft client) {
        if (client == null || client.screen == null) {
            return "none";
        }
        return client.screen.getClass().getSimpleName();
    }

    private static String keyName(KeyMapping key) {
        if (key == null) {
            return "none";
        }
        return key.getTranslatedKeyMessage().getString();
    }
}
