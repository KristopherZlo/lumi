package io.github.luma.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumiShortcutScreenPolicyTest {

    @Test
    void allowsUndoRedoWithNoOpenScreen() {
        LumiShortcutScreenPolicy policy = new LumiShortcutScreenPolicy();

        assertTrue(policy.allowsUndoRedoOnOpenScreen(null));
    }

    @Test
    void allowsUndoRedoFromPauseScreenClassName() {
        LumiShortcutScreenPolicy policy = new LumiShortcutScreenPolicy();

        assertTrue(policy.allowsUndoRedoOnScreenClassName("net.minecraft.client.gui.screens.PauseScreen"));
    }

    @Test
    void allowsUndoRedoFromAxiomScreens() {
        LumiShortcutScreenPolicy policy = new LumiShortcutScreenPolicy();

        assertTrue(policy.allowsUndoRedoOnScreenClassName("com.moulberry.axiom.gui.ToolMenuScreen"));
        assertTrue(policy.allowsUndoRedoOnScreenClassName("net.fabricmc.example.AxiomHandDrawScreen"));
    }

    @Test
    void rejectsUnknownTextOrMenuScreens() {
        LumiShortcutScreenPolicy policy = new LumiShortcutScreenPolicy();

        assertFalse(policy.allowsUndoRedoOnScreenClassName("net.minecraft.client.gui.screens.ChatScreen"));
        assertFalse(policy.allowsUndoRedoOnScreenClassName("net.minecraft.client.gui.screens.inventory.AnvilScreen"));
        assertFalse(policy.allowsUndoRedoOnScreenClassName(""));
    }
}
