package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.UndoRedoActionStack;
import io.github.luma.minecraft.capture.DeferredActionFalloutGuard;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import io.github.luma.minecraft.world.EntityApplyMode;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.WorldChangeBatchPreparer;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * Starts prepared world operations for live undo and redo actions.
 */
public final class UndoRedoService {

    private static final int SERVER_THREAD_COMPLETION_MAX_BLOCKS = 256;

    private final ProjectService projectService = new ProjectService();
    private final UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
    private final HistoryCaptureManager captureManager = HistoryCaptureManager.getInstance();
    private final DeferredActionFalloutGuard deferredActionFalloutGuard = DeferredActionFalloutGuard.getInstance();
    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
    private final WorldChangeBatchPreparer batchPreparer = new WorldChangeBatchPreparer();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    public OperationHandle undo(ServerLevel level, String projectName) throws IOException {
        return this.undo(level, projectName, null);
    }

    public OperationHandle undo(ServerLevel level, String projectName, String actor) throws IOException {
        BuildProject project = this.projectService.loadProject(level.getServer(), projectName);
        EntityMutationTracker.drainPendingSpawns(level.getServer());
        this.captureManager.drainUndoRedoStabilization(level.getServer(), project.id().toString());
        this.ensureStabilizationReady(level, project);
        UndoRedoActionStack.Selection selection = actor == null || actor.isBlank()
                ? this.historyManager.selectUndo(project.id().toString())
                : this.historyManager.selectUndo(project.id().toString(), actor);
        if (selection == null && !level.getServer().isDedicatedServer()) {
            UndoRedoActionStack.Selection fallback = this.historyManager.selectUndo(project.id().toString());
            if (this.canUseSingleplayerFallback(fallback)) {
                selection = fallback;
            }
        }
        if (selection == null) {
            throw new IllegalArgumentException("No Lumi action is available to undo");
        }
        return this.startOperation(level, project, selection, Direction.UNDO);
    }

    public OperationHandle redo(ServerLevel level, String projectName) throws IOException {
        return this.redo(level, projectName, null);
    }

    public OperationHandle redo(ServerLevel level, String projectName, String actor) throws IOException {
        BuildProject project = this.projectService.loadProject(level.getServer(), projectName);
        EntityMutationTracker.drainPendingSpawns(level.getServer());
        this.captureManager.drainUndoRedoStabilization(level.getServer(), project.id().toString());
        this.ensureStabilizationReady(level, project);
        UndoRedoActionStack.Selection selection = actor == null || actor.isBlank()
                ? this.historyManager.selectRedo(project.id().toString())
                : this.historyManager.selectRedo(project.id().toString(), actor);
        if (selection == null && !level.getServer().isDedicatedServer()) {
            UndoRedoActionStack.Selection fallback = this.historyManager.selectRedo(project.id().toString());
            if (this.canUseSingleplayerFallback(fallback)) {
                selection = fallback;
            }
        }
        if (selection == null) {
            throw new IllegalArgumentException("No Lumi action is available to redo");
        }
        return this.startOperation(level, project, selection, Direction.REDO);
    }

    private void ensureStabilizationReady(ServerLevel level, BuildProject project) throws IOException {
        if (this.captureManager.hasPendingUndoRedoStabilization(level.getServer(), project.id().toString())) {
            throw new IllegalStateException("Redstone or piston fallout is still settling; try undo/redo again in a moment");
        }
    }

    private OperationHandle startOperation(
            ServerLevel level,
            BuildProject project,
            UndoRedoActionStack.Selection selection,
            Direction direction
    ) {
        UndoRedoAction action = selection.action();
        List<StoredBlockChange> targetChanges = direction == Direction.UNDO
                ? action.undoChanges()
                : action.redoChanges();
        List<StoredEntityChange> targetEntityChanges = direction == Direction.UNDO
                ? action.undoEntityChanges()
                : action.redoEntityChanges();
        List<StoredBlockChange> pendingAdjustments = direction == Direction.UNDO
                ? action.inverseChanges()
                : action.redoChanges();
        List<StoredEntityChange> pendingEntityAdjustments = direction == Direction.UNDO
                ? action.inverseEntityChanges()
                : action.redoEntityChanges();
        boolean completeOnServerThread = this.canCompleteOnServerThread(targetChanges, targetEntityChanges);
        String label = direction == Direction.UNDO ? "undo-action" : "redo-action";
        EntityBatch.ReplayContext replayContext = this.replayContext(action, direction);
        int totalChanges = targetChanges.size() + targetEntityChanges.size();
        this.deferredActionFalloutGuard.suppressAction(action.id(), level.getGameTime());
        LumaLoadLog.event("undo-redo", "selected-action",
                "direction=" + direction.label()
                        + ", project=" + project.name()
                        + ", action=" + action.id()
                        + ", actor=" + action.actor()
                        + ", targetBlocks=" + targetChanges.size()
                        + ", targetEntities=" + targetEntityChanges.size()
                        + ", adjustmentBlocks=" + pendingAdjustments.size()
                        + ", adjustmentEntities=" + pendingEntityAdjustments.size()
                        + ", completeOnServerThread=" + completeOnServerThread);
        this.historyDebugLog.logUndoRedoSelection(
                project,
                direction.label(),
                action,
                targetChanges,
                targetEntityChanges,
                pendingAdjustments,
                pendingEntityAdjustments
        );

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                label,
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> {
                    progressSink.update(OperationStage.PREPARING, 0, totalChanges, "Decoding " + direction.label());
                    List<PreparedChunkBatch> batches = this.batchPreparer.prepareUndoRedo(
                            level,
                            targetChanges,
                            targetEntityChanges,
                            direction == Direction.REDO,
                            (completed, total) -> progressSink.update(
                                    OperationStage.PREPARING,
                                    completed,
                                    total,
                                    "Decoded " + direction.label()
                            ),
                            EntityApplyMode.DELTA
                    );
                    batches = batches.stream()
                            .map(batch -> batch.withEntityReplayContext(replayContext))
                            .toList();
                    return new WorldOperationManager.PreparedApplyOperation(
                            batches,
                            () -> {
                                if (direction == Direction.UNDO) {
                                    this.historyManager.completeUndo(project.id().toString(), selection);
                                } else {
                                    this.historyManager.completeRedo(project.id().toString(), selection);
                                }
                                this.captureManager.applyLiveActionAdjustments(
                                        level.getServer(),
                                        project.id().toString(),
                                        pendingAdjustments,
                                        pendingEntityAdjustments,
                                        action.actor(),
                                        Instant.now()
                                );
                                LumaMod.LOGGER.info(
                                        "Completed {} for project {} with {} block and {} entity changes",
                                        direction.label(),
                                        project.name(),
                                        targetChanges.size(),
                                        targetEntityChanges.size()
                                );
                            },
                            completeOnServerThread
                    );
                }
        );
    }

    private boolean canCompleteOnServerThread(
            List<StoredBlockChange> targetChanges,
            List<StoredEntityChange> targetEntityChanges
    ) {
        return targetEntityChanges.isEmpty()
                && targetChanges.size() <= SERVER_THREAD_COMPLETION_MAX_BLOCKS;
    }

    private EntityBatch.ReplayContext replayContext(UndoRedoAction action, Direction direction) {
        String actor = WorldMutationContext.currentActor();
        if (actor == null || actor.isBlank() || "world".equals(actor)) {
            actor = action.actor();
        }
        return new EntityBatch.ReplayContext(
                actor,
                direction.label() + "-fallout-" + UUID.randomUUID(),
                true
        );
    }

    private boolean canUseSingleplayerFallback(UndoRedoActionStack.Selection selection) {
        if (selection == null || selection.action() == null) {
            return false;
        }
        return switch (selection.action().actor()) {
            case "explosion", "explosive", "mob" -> true;
            default -> false;
        };
    }

    private enum Direction {
        UNDO("undo"),
        REDO("redo");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        private String label() {
            return this.label;
        }
    }
}
