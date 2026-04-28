package io.github.luma.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.ui.overlay.RecentChangesOverlayCoordinator;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Tracks the held Alt modifier and undo/redo keys as one-shot key chords.
 */
public final class UndoRedoKeyChordTracker {

    private boolean undoHeld;
    private boolean redoHeld;

    public TickResult tick(Minecraft client, boolean altHeld, KeyMapping undoKey, KeyMapping redoKey) {
        boolean undoClicked = this.consumeClicks(undoKey);
        boolean redoClicked = this.consumeClicks(redoKey);
        boolean currentUndoHeld = altHeld && this.isBindingDown(client, undoKey);
        boolean currentRedoHeld = altHeld && this.isBindingDown(client, redoKey);
        boolean undoRequested = altHeld && (undoClicked || (currentUndoHeld && !this.undoHeld));
        boolean redoRequested = altHeld && (redoClicked || (currentRedoHeld && !this.redoHeld));

        TickResult result = new TickResult(
                undoRequested,
                redoRequested,
                (currentRedoHeld || redoRequested) && !(currentUndoHeld || undoRequested)
                        ? RecentChangesOverlayCoordinator.PreviewTarget.REDO
                        : RecentChangesOverlayCoordinator.PreviewTarget.UNDO
        );

        this.undoHeld = currentUndoHeld;
        this.redoHeld = currentRedoHeld;
        return result;
    }

    private boolean consumeClicks(KeyMapping key) {
        boolean clicked = false;
        while (key.consumeClick()) {
            clicked = true;
        }
        return clicked;
    }

    private boolean isBindingDown(Minecraft client, KeyMapping key) {
        if (key.isDown()) {
            return true;
        }
        if (client == null || client.getWindow() == null || key.isUnbound()) {
            return false;
        }
        InputConstants.Key boundKey = InputConstants.getKey(key.saveString());
        if (boundKey.getType() != InputConstants.Type.KEYSYM) {
            return false;
        }
        return InputConstants.isKeyDown(client.getWindow(), boundKey.getValue());
    }

    public record TickResult(
            boolean undoPressed,
            boolean redoPressed,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
    }
}
