package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.UndoRedoActionStack;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory per-project undo/redo history for live builder actions.
 */
public final class UndoRedoHistoryManager {

    private static final UndoRedoHistoryManager INSTANCE = new UndoRedoHistoryManager();

    private final Map<String, UndoRedoActionStack> projectStacks = new HashMap<>();

    private UndoRedoHistoryManager() {
    }

    public static UndoRedoHistoryManager getInstance() {
        return INSTANCE;
    }

    public synchronized void recordChange(
            String projectId,
            String dimensionId,
            String actionId,
            String actor,
            StoredBlockChange change,
            Instant now
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        this.stack(projectId).recordChange(actionId, actor, projectId, dimensionId, change, now);
    }

    public synchronized void recordRelatedChange(
            String projectId,
            String dimensionId,
            StoredBlockChange change,
            Instant now,
            Duration maxIdle,
            int chunkRadius
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        this.stack(projectId).recordRelatedChange(dimensionId, change, now, maxIdle, chunkRadius);
    }

    public synchronized UndoRedoActionStack.Selection selectUndo(String projectId) {
        return this.stack(projectId).selectUndo();
    }

    public synchronized UndoRedoActionStack.Selection selectRedo(String projectId) {
        return this.stack(projectId).selectRedo();
    }

    public synchronized void completeUndo(String projectId, UndoRedoActionStack.Selection selection) {
        this.stack(projectId).completeUndo(selection);
    }

    public synchronized void completeRedo(String projectId, UndoRedoActionStack.Selection selection) {
        this.stack(projectId).completeRedo(selection);
    }

    public synchronized List<UndoRedoAction> recentUndoActions(String projectId, int count) {
        return this.stack(projectId).recentUndoActions(count);
    }

    public synchronized void clearProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        this.stack(projectId).clear();
    }

    private UndoRedoActionStack stack(String projectId) {
        return this.projectStacks.computeIfAbsent(projectId, ignored -> new UndoRedoActionStack());
    }
}
