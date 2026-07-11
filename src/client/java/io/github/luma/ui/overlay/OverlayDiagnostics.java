package io.github.luma.ui.overlay;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.ui.onboarding.KeyGlyphResolver;
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

    private OverlayDiagnostics() {
    }

    public static OverlayDiagnostics getInstance() {
        return INSTANCE;
    }

    public void clientRenderCallbacksRegistered(String stage) {
        LumaDebugLog.log("overlay-render", "Registered compare and pending overlay callbacks at {}", stage);
    }

    public void clientTick(
            Minecraft client,
            boolean overlayHold,
            boolean shortcutInputActive,
            boolean undoPressed,
            boolean redoPressed,
            KeyMapping overlayKey
    ) {
        boolean compareVisible = CompareOverlayRenderer.visible();
        boolean changed = overlayHold != this.lastOverlayHold
                || shortcutInputActive != this.lastShortcutInputActive
                || compareVisible != this.lastCompareVisible;

        this.lastOverlayHold = overlayHold;
        this.lastShortcutInputActive = shortcutInputActive;
        this.lastCompareVisible = compareVisible;

        if (!changed && !overlayHold && !undoPressed && !redoPressed) {
            return;
        }
        if (!this.shouldLog(false, "client-tick", changed || undoPressed || redoPressed)) {
            return;
        }

        LumaDebugLog.log(
                "overlay-input",
                "tick hold={} shortcutActive={} undoPressed={} redoPressed={} compareVisible={} screen={} player={} level={} key={}",
                overlayHold,
                shortcutInputActive,
                undoPressed,
                redoPressed,
                compareVisible,
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
        return KeyGlyphResolver.bracketedLabel(key, "ACTION");
    }
}
