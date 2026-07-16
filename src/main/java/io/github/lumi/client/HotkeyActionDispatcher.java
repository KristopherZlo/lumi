package io.github.lumi.client;

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
        try {
            switch (action) {
                case DASHBOARD -> actions.openDashboard();
                case SAVE -> actions.openSave();
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
                case QUICK_ROLLBACK -> {
                    actions.quickRollback();
                    feedback.accept("luma.status.quick_rollback_started");
                }
            }
        } catch (RuntimeException failed) {
            feedback.accept(failed.getMessage() == null
                    ? "Lumi action could not start" : failed.getMessage());
        }
    }

    public void switchBranch(int slot) {
        if (slot < 0 || slot > 9) {
            throw new IllegalArgumentException("Branch slot must be 0-9");
        }
        try {
            actions.switchBranch(slot);
        } catch (RuntimeException failed) {
            feedback.accept(failed.getMessage() == null
                    ? "Lumi branch could not open" : failed.getMessage());
        }
    }

    public enum Action { DASHBOARD, SAVE, UNDO, REDO, QUICK_ROLLBACK }

    public interface Actions {
        void openDashboard();
        void openSave();
        boolean undoSelection();
        boolean redoSelection();
        void undo();
        void redo();
        void quickRollback();
        void switchBranch(int slot);
    }
}
