package io.github.luma.client.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Suppresses vanilla world interaction while Lumi shortcut chords are active.
 */
public final class LumiShortcutInteractionGate {

    private static final LumiShortcutInteractionGate INSTANCE = new LumiShortcutInteractionGate();
    private static final int POST_SHORTCUT_SUPPRESSION_TICKS = 2;

    private KeyMapping actionKey;
    private KeyMapping undoKey;
    private KeyMapping redoKey;
    private KeyBindingState keyBindingState;
    private int suppressionTicks;

    LumiShortcutInteractionGate() {
    }

    public static LumiShortcutInteractionGate getInstance() {
        return INSTANCE;
    }

    public void configure(
            KeyMapping actionKey,
            KeyMapping undoKey,
            KeyMapping redoKey,
            KeyBindingState keyBindingState
    ) {
        this.actionKey = actionKey;
        this.undoKey = undoKey;
        this.redoKey = redoKey;
        this.keyBindingState = keyBindingState;
    }

    public void tick(boolean inputActive, UndoRedoKeyChordTracker.TickResult shortcutState) {
        boolean shortcutActive = inputActive
                && shortcutState != null
                && (shortcutState.previewActive() || shortcutState.undoPressed() || shortcutState.redoPressed());
        this.tick(inputActive, shortcutActive);
    }

    void tick(boolean inputActive, boolean shortcutActive) {
        if (!inputActive) {
            this.suppressionTicks = 0;
            return;
        }
        if (shortcutActive) {
            this.suppressionTicks = POST_SHORTCUT_SUPPRESSION_TICKS;
        } else if (this.suppressionTicks > 0) {
            this.suppressionTicks -= 1;
        }
    }

    public boolean shouldSuppressWorldInteraction(Minecraft client) {
        if (!this.canInteractWithWorld(client)) {
            return false;
        }
        return this.suppressionTicks > 0 || this.currentShortcutChordDown(client);
    }

    boolean suppressed() {
        return this.suppressionTicks > 0;
    }

    private boolean currentShortcutChordDown(Minecraft client) {
        if (this.keyBindingState == null
                || this.actionKey == null
                || (this.undoKey == null && this.redoKey == null)) {
            return false;
        }
        return this.keyBindingState.isDown(client, this.actionKey)
                && (this.keyBindingState.isDown(client, this.undoKey)
                || this.keyBindingState.isDown(client, this.redoKey));
    }

    private boolean canInteractWithWorld(Minecraft client) {
        return client != null
                && client.screen == null
                && client.player != null
                && client.level != null;
    }
}
