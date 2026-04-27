package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.UndoRedoActionStack;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.world.BlockStateNbtCodec;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Starts prepared world operations for live undo and redo actions.
 */
public final class UndoRedoService {

    private final ProjectService projectService = new ProjectService();
    private final UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    public OperationHandle undo(ServerLevel level, String projectName) throws IOException {
        BuildProject project = this.projectService.loadProject(level.getServer(), projectName);
        UndoRedoActionStack.Selection selection = this.historyManager.selectUndo(project.id().toString());
        if (selection == null) {
            throw new IllegalArgumentException("No Lumi action is available to undo");
        }
        return this.startOperation(level, project, selection, Direction.UNDO);
    }

    public OperationHandle redo(ServerLevel level, String projectName) throws IOException {
        BuildProject project = this.projectService.loadProject(level.getServer(), projectName);
        UndoRedoActionStack.Selection selection = this.historyManager.selectRedo(project.id().toString());
        if (selection == null) {
            throw new IllegalArgumentException("No Lumi action is available to redo");
        }
        return this.startOperation(level, project, selection, Direction.REDO);
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
        String label = direction == Direction.UNDO ? "undo-action" : "redo-action";
        int totalChanges = targetChanges.size() + targetEntityChanges.size();

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                label,
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> {
                    progressSink.update(OperationStage.PREPARING, 0, totalChanges, "Decoding " + direction.label());
                    List<PreparedChunkBatch> batches = this.decodeBatches(level, targetChanges, targetEntityChanges, direction, progressSink);
                    return new WorldOperationManager.PreparedApplyOperation(
                            batches,
                            () -> {
                                if (direction == Direction.UNDO) {
                                    this.historyManager.completeUndo(project.id().toString(), selection);
                                } else {
                                    this.historyManager.completeRedo(project.id().toString(), selection);
                                }
                                HistoryCaptureManager.getInstance().applyUndoRedoAdjustments(
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
                            }
                    );
                }
        );
    }

    private List<PreparedChunkBatch> decodeBatches(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            Direction direction,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        Map<ChunkPoint, List<PreparedBlockPlacement>> grouped = new LinkedHashMap<>();
        Map<ChunkPoint, List<StoredEntityChange>> groupedEntities = new LinkedHashMap<>();
        int index = 0;
        for (StoredBlockChange change : changes) {
            StatePayload target = direction == Direction.UNDO ? change.oldValue() : change.newValue();
            ChunkPoint chunk = ChunkPoint.from(change.pos());
            grouped.computeIfAbsent(chunk, ignored -> new ArrayList<>())
                    .add(new PreparedBlockPlacement(
                            new BlockPos(change.pos().x(), change.pos().y(), change.pos().z()),
                            BlockStateNbtCodec.deserializeBlockState(level, target.stateTag()),
                            target.blockEntityTag() == null ? null : target.blockEntityTag().copy()
                    ));
            index += 1;
            progressSink.update(OperationStage.PREPARING, index, changes.size(), "Decoded " + direction.label());
        }
        for (StoredEntityChange change : entityChanges) {
            groupedEntities.computeIfAbsent(change.chunk(), ignored -> new ArrayList<>()).add(change);
            index += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    index,
                    changes.size() + entityChanges.size(),
                    "Decoded " + direction.label()
            );
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        java.util.LinkedHashSet<ChunkPoint> chunks = new java.util.LinkedHashSet<>();
        chunks.addAll(grouped.keySet());
        chunks.addAll(groupedEntities.keySet());
        for (ChunkPoint chunk : chunks) {
            batches.add(new PreparedChunkBatch(
                    chunk,
                    List.copyOf(grouped.getOrDefault(chunk, List.of())),
                    toEntityBatch(groupedEntities.getOrDefault(chunk, List.of()), direction)
            ));
        }
        return batches;
    }

    private static EntityBatch toEntityBatch(List<StoredEntityChange> changes, Direction direction) {
        List<net.minecraft.nbt.CompoundTag> spawns = new ArrayList<>();
        List<String> removals = new ArrayList<>();
        List<net.minecraft.nbt.CompoundTag> updates = new ArrayList<>();
        for (StoredEntityChange change : changes) {
            StoredEntityChange target = direction == Direction.UNDO ? change.inverse() : change;
            if (target.isSpawn()) {
                spawns.add(target.newValue().copyTag());
            } else if (target.isRemove()) {
                removals.add(target.entityId());
            } else if (target.isUpdate()) {
                updates.add(target.newValue().copyTag());
            }
        }
        return new EntityBatch(spawns, removals, updates);
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
