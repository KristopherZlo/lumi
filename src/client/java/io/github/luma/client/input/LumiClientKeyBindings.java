package io.github.luma.client.input;

import net.minecraft.client.KeyMapping;

/**
 * Client key binding registry for UI surfaces that need to render or inspect Lumi shortcuts.
 */
public final class LumiClientKeyBindings {

    private static volatile Bindings bindings = Bindings.empty();

    private LumiClientKeyBindings() {
    }

    public static void configure(
            KeyMapping openWorkspace,
            KeyMapping quickSave,
            KeyMapping undo,
            KeyMapping redo,
            KeyMapping compare,
            KeyMapping quickRollback,
            KeyMapping action,
            KeyMapping info
    ) {
        bindings = new Bindings(openWorkspace, quickSave, undo, redo, compare, quickRollback, action, info);
    }

    public static KeyMapping key(Role role) {
        return bindings.key(role);
    }

    public enum Role {
        OPEN_WORKSPACE,
        QUICK_SAVE,
        UNDO,
        REDO,
        COMPARE,
        QUICK_ROLLBACK,
        ACTION,
        INFO
    }

    private record Bindings(
            KeyMapping openWorkspace,
            KeyMapping quickSave,
            KeyMapping undo,
            KeyMapping redo,
            KeyMapping compare,
            KeyMapping quickRollback,
            KeyMapping action,
            KeyMapping info
    ) {
        private static Bindings empty() {
            return new Bindings(null, null, null, null, null, null, null, null);
        }

        private KeyMapping key(Role role) {
            return switch (role) {
                case OPEN_WORKSPACE -> this.openWorkspace;
                case QUICK_SAVE -> this.quickSave;
                case UNDO -> this.undo;
                case REDO -> this.redo;
                case COMPARE -> this.compare;
                case QUICK_ROLLBACK -> this.quickRollback;
                case ACTION -> this.action;
                case INFO -> this.info;
            };
        }
    }
}
