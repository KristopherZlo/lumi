package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoRedoServiceDiagnosticsTest {

    @Test
    void undoRedoSelectionLogsReplayCountsForDiagnostics() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));

        assertTrue(source.contains("LumaLoadLog.event(\"undo-redo\", \"selected-action\""));
        assertTrue(source.contains("targetBlocks=\" + targetChanges.size()"));
        assertTrue(source.contains("targetEntities=\" + targetEntityChanges.size()"));
        assertTrue(source.contains("adjustmentBlocks=\" + pendingAdjustments.size()"));
    }

    @Test
    void restoredEntityReplayReceivesCausalContextForFollowUpMobFallout() throws Exception {
        String undoRedoService = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));
        String quickRollbackService = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/QuickRollbackService.java"));
        String applier = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/world/BlockChangeApplier.java"));

        assertTrue(undoRedoService.contains("new EntityBatch.ReplayContext("));
        assertTrue(undoRedoService.contains("batch.withEntityReplayContext(replayContext)"));
        assertTrue(quickRollbackService.contains("batch.withEntityReplayContext(replayContext)"));
        assertTrue(applier.contains("rememberReplayAction("));
    }

    @Test
    void playerScopedUndoRedoSelectsActorStackWhenActorIsKnown() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));

        assertTrue(source.contains("undo(ServerLevel level, String projectName, String actor)"));
        assertTrue(source.contains("redo(ServerLevel level, String projectName, String actor)"));
        assertTrue(source.contains("this.historyManager.selectUndo(project.id().toString(), actor)"));
        assertTrue(source.contains("this.historyManager.selectRedo(project.id().toString(), actor)"));
    }

    @Test
    void singleplayerUndoRedoFallsBackToProjectWideActionsAfterActorMiss() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));

        assertTrue(source.contains("selection == null && !level.getServer().isDedicatedServer()"));
        assertTrue(source.contains("this.historyManager.selectUndo(project.id().toString())"));
        assertTrue(source.contains("this.historyManager.selectRedo(project.id().toString())"));
    }

    @Test
    void undoRedoInterruptsActiveExplosiveContextsInsteadOfWaiting() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/domain/service/UndoRedoService.java"));

        int undoMethod = source.indexOf("public OperationHandle undo(ServerLevel level, String projectName, String actor)");
        int redoMethod = source.indexOf("public OperationHandle redo(ServerLevel level, String projectName, String actor)");
        int undoFreezeIndex = source.indexOf("FreezeDecision freezeDecision = this.freezeDecision(selection.action())", undoMethod);
        int redoFreezeIndex = source.indexOf("FreezeDecision freezeDecision = this.freezeDecision(selection.action())", redoMethod);
        int undoUnavailableIndex = source.indexOf("throw new IllegalArgumentException(\"No Lumi action is available to undo\")", undoMethod);
        int redoUnavailableIndex = source.indexOf("throw new IllegalArgumentException(\"No Lumi action is available to redo\")", redoMethod);

        assertTrue(source.contains("ExplosiveEntityContextRegistry"));
        assertTrue(source.contains("runOnServerThread(level, () -> this.undoOnServerThread"));
        assertTrue(source.contains("runOnServerThread(level, () -> this.redoOnServerThread"));
        assertTrue(source.contains("withActiveExplosiveInterruptions(level, action, selectedTargetEntityChanges)"));
        assertTrue(source.contains("activeEntityIdsForAction(action.id())"));
        assertFalse(source.contains("TNT fallout is still settling"));
        assertFalse(source.contains("ensureStabilizationReady(level, project, selection.action())"));
        assertTrue(undoFreezeIndex >= 0);
        assertTrue(redoFreezeIndex >= 0);
        assertTrue(undoFreezeIndex > undoUnavailableIndex);
        assertTrue(redoFreezeIndex > redoUnavailableIndex);
        assertTrue(source.contains("this.startOperation(level, project, selection, Direction.UNDO, freezeDecision)"));
        assertTrue(source.contains("this.startOperation(level, project, selection, Direction.REDO, freezeDecision)"));
        assertTrue(source.contains("freezeReason="));
        assertTrue(source.contains("activeExplosiveContexts="));
    }

    @Test
    void selectedTntBlockReplayRequiresWorldTickFreeze() {
        UndoRedoAction action = action(List.of(change("minecraft:air", "minecraft:tnt")), List.of());

        assertTrue(UndoRedoService.requiresWorldTickFreeze(action));
    }

    @Test
    void selectedPrimedTntEntityReplayRequiresWorldTickFreeze() {
        UndoRedoAction action = action(List.of(), List.of(new StoredEntityChange(
                "00000000-0000-0000-0000-000000000090",
                "minecraft:tnt",
                null,
                entity("minecraft:tnt")
        )));

        assertTrue(UndoRedoService.requiresWorldTickFreeze(action));
    }

    @Test
    void ordinaryBlockReplayDoesNotRequireWorldTickFreeze() {
        UndoRedoAction action = action(List.of(change("minecraft:stone", "minecraft:dirt")), List.of());

        assertFalse(UndoRedoService.requiresWorldTickFreeze(action));
    }

    private static UndoRedoAction action(List<StoredBlockChange> blocks, List<StoredEntityChange> entities) {
        UndoRedoAction action = new UndoRedoAction(
                "action",
                "Alex",
                "project",
                "minecraft:overworld",
                Instant.EPOCH,
                Instant.EPOCH
        );
        for (StoredBlockChange block : blocks) {
            action.recordChange(block, Instant.EPOCH);
        }
        for (StoredEntityChange entity : entities) {
            action.recordEntityChange(entity, Instant.EPOCH);
        }
        return action;
    }

    private static StoredBlockChange change(String oldBlock, String newBlock) {
        return new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                new StatePayload(state(oldBlock), null),
                new StatePayload(state(newBlock), null)
        );
    }

    private static EntityPayload entity(String entityType) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entityType);
        return new EntityPayload(tag);
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
