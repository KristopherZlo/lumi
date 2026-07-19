package io.github.lumi.client;

import io.github.lumi.LumiMod;
import java.util.Objects;
import java.util.function.Consumer;

/** Maps one accepted client chord to one intent and immediate local feedback. */
public final class HotkeyActionDispatcher {
    private final Actions actions;
    private final Consumer<String> feedback;

    public HotkeyActionDispatcher(Actions actions, Consumer<String> feedback) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    public void dispatch(Action action) {
        Objects.requireNonNull(action, "action");
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
                        feedback.accept("luma.status.undo_started");
                    }
                }
                case REDO -> {
                    if (actions.redoSelection()) {
                        feedback.accept("luma.selection.redo");
                    } else {
                        actions.redo();
                        feedback.accept("luma.status.redo_started");
                    }
                }
                case COMPARE_OVERLAY -> feedback.accept(actions.toggleCompareOverlay());
                case QUICK_ROLLBACK -> {
                    actions.quickRollback();
                    feedback.accept("luma.status.quick_rollback_started");
                }
            }
        } catch (RuntimeException failed) {
            LumiMod.LOGGER.warn(
                    "Lumi client hotkey action {} could not start", action, failed);
            feedback.accept(failed.getMessage() == null
                    ? "Lumi action could not start" : failed.getMessage());
        }
    }

    public void switchBranch(int slot) {
        if (slot < 0 || slot > 9) {
            throw new IllegalArgumentException("Branch slot must be 0-9");
        }
        LumiMod.LOGGER.info("Lumi client branch hotkey invoked: slot={}", slot);
        try {
            actions.switchBranch(slot);
        } catch (RuntimeException failed) {
            LumiMod.LOGGER.warn(
                    "Lumi client branch hotkey {} could not start", slot, failed);
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
        void switchBranch(int slot);
    }
}
