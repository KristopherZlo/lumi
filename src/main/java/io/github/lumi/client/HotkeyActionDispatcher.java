package io.github.lumi.client;

import io.github.lumi.LumiMod;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Maps one accepted client chord to one intent and immediate local feedback. */
public final class HotkeyActionDispatcher {
    private final Actions actions;
    private final Consumer<String> feedback;
    private final BooleanSupplier enabled;

    public HotkeyActionDispatcher(Actions actions, Consumer<String> feedback) {
        this(actions, feedback, () -> true);
    }

    public HotkeyActionDispatcher(
            Actions actions,
            Consumer<String> feedback,
            BooleanSupplier enabled) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    public void dispatch(Action action) {
        Objects.requireNonNull(action, "action");
        if (!enabled.getAsBoolean()) {
            feedback.accept("luma.status.survival_disabled");
            return;
        }
        LumiMod.LOGGER.info("Lumi client hotkey action invoked: {}", action);
        try {
            switch (action) {
                case DASHBOARD -> actions.openDashboard();
                case SAVE -> actions.openSave();
                case HOTKEYS -> actions.openHotkeys();
                case UNDO -> {
                    if (actions.undoSelection()) {
                        feedback.accept("luma.selection.undo");
                    } else {
                        actions.undo();
                    }
                }
                case REDO -> {
                    if (actions.redoSelection()) {
                        feedback.accept("luma.selection.redo");
                    } else {
                        actions.redo();
                    }
                }
                case COMPARE_OVERLAY -> feedback.accept(actions.toggleCompareOverlay());
                case QUICK_ROLLBACK -> {
                    actions.quickRollback();
                }
            }
        } catch (RuntimeException failed) {
            LumiMod.LOGGER.warn(
                    "Lumi client hotkey action {} could not start", action, failed);
            feedback.accept(failed.getMessage() == null
                    ? "Lumi action could not start" : failed.getMessage());
        }
    }

    public void switchBranch(int keyCode) {
        if (keyCode < 32 || keyCode > 348) {
            throw new IllegalArgumentException("Invalid branch shortcut key");
        }
        if (!enabled.getAsBoolean()) {
            feedback.accept("luma.status.survival_disabled");
            return;
        }
        LumiMod.LOGGER.info(
                "Lumi client branch hotkey invoked: key={}", keyCode);
        try {
            actions.switchBranch(keyCode);
        } catch (RuntimeException failed) {
            LumiMod.LOGGER.warn(
                    "Lumi client branch hotkey {} could not start", keyCode, failed);
            feedback.accept(failed.getMessage() == null
                    ? "Lumi branch could not open" : failed.getMessage());
        }
    }

    public enum Action {
        DASHBOARD, SAVE, HOTKEYS, UNDO, REDO, COMPARE_OVERLAY, QUICK_ROLLBACK
    }

    public interface Actions {
        void openDashboard();
        void openSave();
        void openHotkeys();
        boolean undoSelection();
        boolean redoSelection();
        void undo();
        void redo();
        String toggleCompareOverlay();
        void quickRollback();
        void switchBranch(int keyCode);
    }
}
