package io.github.luma.client.input;

/**
 * Marks screens that should observe Lumi keybindings without letting the
 * normal in-world shortcut handlers execute them.
 */
public interface LumiShortcutSuppressingScreen {

    boolean suppressesLumiShortcuts();
}
