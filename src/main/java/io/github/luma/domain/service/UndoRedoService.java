package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.UndoRedoActionStack;
import io.github.luma.minecraft.capture.DeferredWorldMutationContext;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.world.EntityApplyMode;
import io.github.luma.minecraft.world.PreparedApplyOperation;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.WorldChangeBatchPreparer;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import net.minecraft.server.level.ServerLevel;

/** Applies a recorded live action backward or forward. */
public final class UndoRedoService {

    private static final int INLINE_COMPLETION_LIMIT = 256;

    private final ProjectService projectService = new ProjectService();
    private final UndoRedoHistoryManager history = UndoRedoHistoryManager.getInstance();
    private final HistoryCaptureManager capture = HistoryCaptureManager.getInstance();
    private final WorldChangeBatchPreparer batches = new WorldChangeBatchPreparer();
    private final WorldOperationManager operations = WorldOperationManager.getInstance();

    public OperationHandle undo(ServerLevel level, String projectName) throws IOException {
        return this.undo(level, projectName, null);
    }

    public OperationHandle undo(ServerLevel level, String projectName, String actor) throws IOException {
        return this.onServerThread(level, () -> this.start(level, projectName, actor, Direction.UNDO));
    }

    public OperationHandle redo(ServerLevel level, String projectName) throws IOException {
        return this.redo(level, projectName, null);
    }

    public OperationHandle redo(ServerLevel level, String projectName, String actor) throws IOException {
        return this.onServerThread(level, () -> this.start(level, projectName, actor, Direction.REDO));
    }

    private OperationHandle start(
            ServerLevel level,
            String projectName,
            String actor,
            Direction direction
    ) throws IOException {
        BuildProject project = this.projectService.loadProject(level.getServer(), projectName);
        String projectId = project.id().toString();
        EntityMutationTracker.drainPendingSpawns(level.getServer());
        this.capture.drainUndoRedoStabilization(level.getServer(), projectId);
        if (this.capture.hasPendingUndoRedoStabilization(level.getServer(), projectId)) {
            throw new IllegalStateException("Block updates are still settling; try undo/redo again in a moment");
        }
        UndoRedoActionStack.Selection selection = direction.select(this.history, projectId, actor);
        if (selection == null && !level.getServer().isDedicatedServer()) {
            selection = direction.select(this.history, projectId, null);
        }
        if (selection == null) {
            throw new IllegalArgumentException("No Lumi action is available to " + direction.label);
        }

        UndoRedoAction action = selection.action();
        List<StoredBlockChange> blocks = action.redoChanges();
        List<StoredEntityChange> entities = action.redoEntityChanges();
        List<StoredBlockChange> draftBlocks = direction == Direction.UNDO
                ? action.inverseChanges()
                : action.redoChanges();
        List<StoredEntityChange> draftEntities = direction == Direction.UNDO
                ? action.inverseEntityChanges()
                : action.redoEntityChanges();
        int total = blocks.size() + entities.size();
        UndoRedoActionStack.Selection selected = selection;
        LumaMod.LOGGER.info(
                "Starting {} for project {} action {} by {}: {} block and {} entity changes",
                direction.label,
                project.name(),
                action.id(),
                action.actor(),
                blocks.size(),
                entities.size()
        );

        return this.operations.startPreparedApplyOperation(
                level,
                projectId,
                direction.operationLabel,
                "changes",
                LumaDebugLog.enabled(project),
                progress -> {
                    progress.update(OperationStage.PREPARING, 0, total, "Decoding " + direction.label);
                    List<PreparedChunkBatch> prepared = this.batches.prepare(
                            level,
                            blocks,
                            entities,
                            direction == Direction.REDO,
                            (completed, count) -> progress.update(
                                    OperationStage.PREPARING,
                                    completed,
                                    count,
                                    "Decoded " + direction.label
                            ),
                            EntityApplyMode.DELTA
                    );
                    return new PreparedApplyOperation(
                            prepared,
                            () -> this.complete(
                                    level,
                                    project,
                                    selected,
                                    direction,
                                    draftBlocks,
                                    draftEntities
                            ),
                            entities.isEmpty() && blocks.size() <= INLINE_COMPLETION_LIMIT,
                            DeferredWorldMutationContext.replayedExplosiveAction(action.actor(), action.id())
                    );
                },
                true
        );
    }

    private void complete(
            ServerLevel level,
            BuildProject project,
            UndoRedoActionStack.Selection selection,
            Direction direction,
            List<StoredBlockChange> draftBlocks,
            List<StoredEntityChange> draftEntities
    ) throws IOException {
        String projectId = project.id().toString();
        boolean moved = direction.complete(this.history, projectId, selection);
        if (!moved) {
            throw new IllegalStateException("Undo/redo history changed before completion; try again");
        }
        try {
            this.capture.applyUndoRedoAdjustments(
                    level.getServer(),
                    projectId,
                    draftBlocks,
                    draftEntities,
                    selection.action().actor(),
                    Instant.now()
            );
        } catch (Exception exception) {
            if (!direction.rollback(this.history, projectId, selection)) {
                LumaMod.LOGGER.error("Could not roll back {} history state for action {}",
                        direction.label, selection.action().id());
            }
            throw exception;
        }
        LumaMod.LOGGER.info("Completed {} for project {}: {} block and {} entity changes",
                direction.label, project.name(), draftBlocks.size(), draftEntities.size());
    }

    private OperationHandle onServerThread(ServerLevel level, OperationTask task) throws IOException {
        if (level == null || level.getServer() == null || level.getServer().isSameThread()) {
            return task.run();
        }
        try {
            return level.getServer().submit(() -> {
                try {
                    return task.run();
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private enum Direction {
        UNDO("undo", "undo-action"),
        REDO("redo", "redo-action");

        private final String label;
        private final String operationLabel;

        Direction(String label, String operationLabel) {
            this.label = label;
            this.operationLabel = operationLabel;
        }

        private UndoRedoActionStack.Selection select(
                UndoRedoHistoryManager history,
                String projectId,
                String actor
        ) {
            if (this == UNDO) {
                return actor == null || actor.isBlank()
                        ? history.selectUndo(projectId)
                        : history.selectUndo(projectId, actor);
            }
            return actor == null || actor.isBlank()
                    ? history.selectRedo(projectId)
                    : history.selectRedo(projectId, actor);
        }

        private boolean complete(
                UndoRedoHistoryManager history,
                String projectId,
                UndoRedoActionStack.Selection selection
        ) {
            return this == UNDO
                    ? history.completeUndo(projectId, selection)
                    : history.completeRedo(projectId, selection);
        }

        private boolean rollback(
                UndoRedoHistoryManager history,
                String projectId,
                UndoRedoActionStack.Selection selection
        ) {
            return this == UNDO
                    ? history.completeRedo(projectId, selection)
                    : history.completeUndo(projectId, selection);
        }
    }

    @FunctionalInterface
    private interface OperationTask {
        OperationHandle run() throws IOException;
    }
}
