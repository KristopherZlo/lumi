package io.github.luma.minecraft.testing;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.luma.client.input.KeyBindingState;
import io.github.luma.client.input.LumiClientKeyBindings;
import io.github.luma.client.input.UndoRedoKeyChordTracker;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

@SuppressWarnings("UnstableApiUsage")
final class HistoryJourneyKeyDriver {

    private final KeyBindingState keyBindingState = new KeyBindingState();
    private final UndoRedoKeyChordTracker chordTracker = new UndoRedoKeyChordTracker();

    void pressUndo(ClientGameTestContext context) throws Exception {
        this.pressChord(context, LumiClientKeyBindings.Role.UNDO);
    }

    void pressRedo(ClientGameTestContext context) throws Exception {
        this.pressChord(context, LumiClientKeyBindings.Role.REDO);
    }

    private void pressChord(ClientGameTestContext context, LumiClientKeyBindings.Role role) throws Exception {
        KeyMapping action = context.computeOnClient(client -> this.requiredKey(LumiClientKeyBindings.Role.ACTION));
        KeyMapping target = context.computeOnClient(client -> this.requiredKey(role));
        KeyMapping undo = context.computeOnClient(client -> this.requiredKey(LumiClientKeyBindings.Role.UNDO));
        KeyMapping redo = context.computeOnClient(client -> this.requiredKey(LumiClientKeyBindings.Role.REDO));
        context.getInput().holdAlt();
        context.getInput().holdKey(target);
        try {
            context.runOnClient(client -> {
                action.setDown(true);
                target.setDown(true);
                KeyMapping.click(InputConstants.getKey(target.saveString()));
            });
            boolean detected = context.computeOnClient(client -> this.detectChord(client, role, action, undo, redo));
            if (!detected) {
                throw new AssertionError("Lumi " + role + " key chord was not detected by the client key bindings");
            }
        } finally {
            context.getInput().releaseKey(target);
            context.getInput().releaseAlt();
            context.runOnClient(client -> {
                target.setDown(false);
                action.setDown(false);
            });
        }
        context.waitTick();
    }

    private boolean detectChord(
            Minecraft client,
            LumiClientKeyBindings.Role role,
            KeyMapping action,
            KeyMapping undo,
            KeyMapping redo
    ) {
        UndoRedoKeyChordTracker.TickResult result = this.chordTracker.tick(
                client,
                true,
                this.keyBindingState.isDown(client, action),
                undo,
                redo
        );
        boolean undoRequested = role == LumiClientKeyBindings.Role.UNDO && result.undoPressed();
        boolean redoRequested = role == LumiClientKeyBindings.Role.REDO && result.redoPressed();
        return undoRequested || redoRequested;
    }

    private KeyMapping requiredKey(LumiClientKeyBindings.Role role) {
        KeyMapping key = LumiClientKeyBindings.key(role);
        if (key == null) {
            throw new IllegalStateException("Lumi key binding is not configured: " + role);
        }
        return key;
    }
}
