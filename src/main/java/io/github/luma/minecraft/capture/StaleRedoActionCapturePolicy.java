package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import net.minecraft.core.BlockPos;

final class StaleRedoActionCapturePolicy {

    private final CaptureDiagnosticsLogger diagnosticsLogger;
    private final UndoRedoHistoryManager undoRedoHistoryManager;

    StaleRedoActionCapturePolicy(CaptureDiagnosticsLogger diagnosticsLogger) {
        this(diagnosticsLogger, UndoRedoHistoryManager.getInstance());
    }

    StaleRedoActionCapturePolicy(
            CaptureDiagnosticsLogger diagnosticsLogger,
            UndoRedoHistoryManager undoRedoHistoryManager
    ) {
        this.diagnosticsLogger = diagnosticsLogger;
        this.undoRedoHistoryManager = undoRedoHistoryManager;
    }

    boolean shouldSkip(
            TrackedProject trackedProject,
            BlockPos pos,
            CaptureSessionState.DeferredActionContext deferredActionContext
    ) {
        if (trackedProject == null) {
            return false;
        }
        String actionId = this.actionId(deferredActionContext);
        if (!this.undoRedoHistoryManager.hasRedoAction(trackedProject.project().id().toString(), actionId)) {
            return false;
        }
        this.diagnosticsLogger.logSkippedCapture(
                trackedProject,
                WorldMutationContext.currentSource(),
                pos,
                "stale-redo-action",
                "action " + actionId + " is already in redo history"
        );
        return true;
    }

    private String actionId(CaptureSessionState.DeferredActionContext context) {
        String actionId = context == null ? "" : context.actionId();
        return actionId == null || actionId.isBlank() ? WorldMutationContext.currentActionId() : actionId;
    }
}
