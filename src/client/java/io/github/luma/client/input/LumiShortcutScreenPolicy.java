package io.github.luma.client.input;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * Separates in-world shortcuts from undo/redo chords that must survive builder tool screens.
 */
public final class LumiShortcutScreenPolicy {

    private static final String PAUSE_SCREEN_CLASS = "net.minecraft.client.gui.screens.PauseScreen";

    public boolean worldInputActive(Minecraft client, boolean shortcutsSuppressed) {
        return this.hasWorldContext(client)
                && !shortcutsSuppressed
                && client.screen == null;
    }

    public boolean undoRedoInputActive(Minecraft client, boolean shortcutsSuppressed) {
        return this.hasWorldContext(client)
                && !shortcutsSuppressed
                && this.allowsUndoRedoOnOpenScreen(client.screen);
    }

    boolean allowsUndoRedoOnOpenScreen(Screen screen) {
        if (screen == null) {
            return true;
        }
        if (screen instanceof PauseScreen) {
            return true;
        }
        return this.allowsUndoRedoOnScreenClassName(screen.getClass().getName());
    }

    boolean allowsUndoRedoOnScreenClassName(String screenClassName) {
        if (screenClassName == null || screenClassName.isBlank()) {
            return false;
        }
        String normalized = screenClassName.trim().toLowerCase(Locale.ROOT);
        return PAUSE_SCREEN_CLASS.toLowerCase(Locale.ROOT).equals(normalized)
                || normalized.contains("axiom");
    }

    private boolean hasWorldContext(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null;
    }
}
