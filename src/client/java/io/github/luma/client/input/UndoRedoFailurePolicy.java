package io.github.luma.client.input;

import io.github.luma.ui.ActionBarMessagePresenter;
import net.minecraft.network.chat.Component;

/**
 * Maps undo/redo startup failures to user-facing status and retry behavior.
 */
final class UndoRedoFailurePolicy {

    private static final String ADMIN_REQUIRED = "luma.status.admin_required";
    private static final String OPERATION_FAILED = "luma.status.operation_failed";
    private static final String SURVIVAL_DISABLED = "luma.status.survival_disabled";
    private static final String SETTLING = "luma.status.undo_redo_settling";
    private static final String UNDO_UNAVAILABLE = "luma.status.undo_unavailable";
    private static final String REDO_UNAVAILABLE = "luma.status.redo_unavailable";
    private static final String WORLD_OPERATION_BUSY = "luma.status.world_operation_busy";

    String statusKey(Exception exception, boolean undo) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("admin permissions") || message.contains("cheats enabled")) {
            return ADMIN_REQUIRED;
        }
        if (message.contains("disabled for survival mode")) {
            return SURVIVAL_DISABLED;
        }
        if (message.contains("Another world operation is already running")) {
            return WORLD_OPERATION_BUSY;
        }
        if (message.contains("still settling")) {
            return SETTLING;
        }
        if (message.contains("No Lumi action") || message.contains("No active Lumi workspace")) {
            return undo ? UNDO_UNAVAILABLE : REDO_UNAVAILABLE;
        }
        return OPERATION_FAILED;
    }

    boolean shouldRetry(String statusKey) {
        return WORLD_OPERATION_BUSY.equals(statusKey)
                || SETTLING.equals(statusKey);
    }

    Component statusMessage(String key) {
        if (OPERATION_FAILED.equals(key)
                || WORLD_OPERATION_BUSY.equals(key)
                || ADMIN_REQUIRED.equals(key)
                || SURVIVAL_DISABLED.equals(key)) {
            return ActionBarMessagePresenter.error(key);
        }
        return ActionBarMessagePresenter.warning(key);
    }
}
