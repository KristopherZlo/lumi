package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoRedoActionStackTest {

    private static final Instant NOW = Instant.parse("2026-04-23T08:00:00Z");

    @Test
    void groupsRepeatedPositionsInsideOneAction() {
        UndoRedoActionStack stack = new UndoRedoActionStack();

        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);
        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:dirt", "minecraft:gold_block"), NOW);

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        assertNotNull(selection);
        assertEquals(1, selection.action().size());
        StoredBlockChange change = selection.action().undoChanges().getFirst();
        assertEquals("minecraft:stone", change.oldValue().blockId());
        assertEquals("minecraft:gold_block", change.newValue().blockId());
    }

    @Test
    void undoAndRedoMoveActionsBetweenStacks() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);

        UndoRedoActionStack.Selection undo = stack.selectUndo();
        stack.completeUndo(undo);

        assertFalse(stack.canUndo());
        assertTrue(stack.canRedo());
        assertEquals(List.of("action-1"), stack.recentRedoActions(1).stream().map(UndoRedoAction::id).toList());

        UndoRedoActionStack.Selection redo = stack.selectRedo();
        stack.completeRedo(redo);

        assertTrue(stack.canUndo());
        assertFalse(stack.canRedo());
    }

    @Test
    void recentPreviewCopiesAreCappedWithoutTruncatingUndoHistory() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "blast", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:air"), NOW);
        recordChange(stack, "blast", "Alex", "project", "minecraft:overworld", change(2, "minecraft:stone", "minecraft:air"), NOW);
        recordChange(stack, "blast", "Alex", "project", "minecraft:overworld", change(3, "minecraft:stone", "minecraft:air"), NOW);

        UndoRedoAction preview = stack.recentUndoActionPreviews(1, 2).getFirst();

        assertEquals(2, preview.size());
        assertEquals(3, stack.selectUndo().action().size());
    }

    @Test
    void delayedEntityFalloutAfterUndoDoesNotReopenUndoAction() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String cowId = "00000000-0000-0000-0000-000000000060";
        recordChange(stack, "kill-cow", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:stone", "minecraft:air"), NOW);
        stack.completeUndo(stack.selectUndo());

        recordDelayedEntityChange(stack,
                "kill-cow",
                "Alex",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(cowId, "minecraft:cow", entity("minecraft:cow", cowId, 1.0D), null),
                NOW,
                NOW.plusSeconds(1)
        );

        assertFalse(stack.canUndo());
        assertTrue(stack.canRedo());
        assertEquals("kill-cow", stack.recentRedoActions(1).getFirst().id());
    }

    @Test
    void linkedBlockFalloutAfterUndoDoesNotReopenRedoAction() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "blast", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:stone", "minecraft:air"), NOW);
        stack.completeUndo(stack.selectUndo());

        recordCurrentCausalChange(stack,
                "blast",
                "Alex",
                "project",
                "minecraft:overworld",
                change(2, "minecraft:stone", "minecraft:air"),
                NOW.plusSeconds(1)
        );

        assertFalse(stack.canUndo());
        assertTrue(stack.canRedo());
        assertEquals("blast", stack.recentRedoActions(1).getFirst().id());
    }

    @Test
    void newActionClearsRedoStack() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);
        stack.completeUndo(stack.selectUndo());

        recordChange(stack, "action-2", "Alex", "project", "minecraft:overworld", change(2, "minecraft:air", "minecraft:oak_planks"), NOW);

        assertFalse(stack.canRedo());
        assertEquals("action-2", stack.recentUndoActions(1).getFirst().id());
    }

    @Test
    void clearDropsUndoAndRedoStacks() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);
        stack.completeUndo(stack.selectUndo());

        stack.clear();

        assertFalse(stack.canUndo());
        assertFalse(stack.canRedo());
    }

    @Test
    void causalSecondaryChangesDoNotClearRedoStack() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);
        recordChange(stack, "action-2", "Alex", "project", "minecraft:overworld", change(2, "minecraft:air", "minecraft:oak_planks"), NOW);
        stack.completeUndo(stack.selectUndo());

        recordCurrentCausalChange(stack,
                "action-1",
                "Alex",
                "project",
                "minecraft:overworld",
                change(3, "minecraft:air", "minecraft:water"),
                NOW.plusSeconds(2)
        );

        assertTrue(stack.canRedo());
        assertEquals("action-2", stack.recentRedoActions(1).getFirst().id());
        assertEquals(2, stack.recentUndoActions(1).getFirst().size());
    }

    @Test
    void staleSelectionDoesNotCompleteAfterSameActionChanges() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        recordCurrentCausalChange(stack,
                "action-1",
                "Alex",
                "project",
                "minecraft:overworld",
                change(2, "minecraft:air", "minecraft:water"),
                NOW.plusSeconds(2)
        );
        stack.completeUndo(selection);

        assertTrue(stack.canUndo());
        assertFalse(stack.canRedo());
        assertEquals(2, stack.recentUndoActions(1).getFirst().size());
    }

    @Test
    void duplicateWriteDoesNotAdvanceRevisionOrClearRedo() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        StoredBlockChange original = change(1, "minecraft:stone", "minecraft:dirt");
        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", original, NOW);
        recordChange(stack, "action-2", "Alex", "project", "minecraft:overworld",
                change(2, "minecraft:air", "minecraft:oak_planks"), NOW.plusSeconds(1));
        stack.completeUndo(stack.selectUndo());
        long revision = stack.revision();

        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", original, NOW.plusSeconds(2));

        assertEquals(revision, stack.revision());
        assertTrue(stack.canRedo());
        assertEquals("action-2", stack.recentRedoActions(1).getFirst().id());
    }

    @Test
    void linkedChangesJoinOlderActionWithoutPromotion() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "redstone-toggle", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:air", "minecraft:lever"), NOW);
        recordChange(stack, "latest-placement", "Alex", "project", "minecraft:overworld",
                change(30, "minecraft:air", "minecraft:stone"), NOW.plusSeconds(1));

        recordCurrentCausalChange(stack,
                "redstone-toggle",
                "Alex",
                "project",
                "minecraft:overworld",
                change(2, "minecraft:repeater", "minecraft:comparator"),
                NOW.plusSeconds(2)
        );

        List<UndoRedoAction> recent = stack.recentUndoActions(2);
        assertEquals(List.of("latest-placement", "redstone-toggle"), recent.stream().map(UndoRedoAction::id).toList());
        assertEquals(1, recent.getFirst().size());
        assertEquals(2, recent.get(1).size());
    }

    @Test
    void causalChangesCanStillJoinCurrentTopAction() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "redstone-toggle", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:air", "minecraft:lever"), NOW);

        recordCurrentCausalChange(stack,
                "redstone-toggle",
                "Alex",
                "project",
                "minecraft:overworld",
                change(2, "minecraft:repeater", "minecraft:comparator"),
                NOW.plusSeconds(1)
        );

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        assertNotNull(selection);
        assertEquals("redstone-toggle", selection.action().id());
        assertEquals(2, selection.action().size());
    }

    @Test
    void causalFluidUsesLatestAppliedStateFromOlderAction() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "pre-cut-gap", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:grass_block", "minecraft:air"), NOW);
        recordChange(stack, "release-water", "Alex", "project", "minecraft:overworld",
                change(2, "minecraft:grass_block", "minecraft:air"), NOW.plusSeconds(1));

        recordCurrentCausalChange(stack,
                "release-water",
                "Alex",
                "project",
                "minecraft:overworld",
                change(1, "minecraft:grass_block", "minecraft:water"),
                NOW.plusSeconds(2)
        );

        UndoRedoAction action = stack.selectUndo().action();
        StoredBlockChange floodedGap = changeAt(action, 1);
        assertEquals("minecraft:air", floodedGap.oldValue().blockId());
        assertEquals("minecraft:water", floodedGap.newValue().blockId());
        StoredBlockChange gateway = changeAt(action, 2);
        assertEquals("minecraft:grass_block", gateway.oldValue().blockId());
        assertEquals("minecraft:air", gateway.newValue().blockId());
    }

    @Test
    void causalFluidRestoresBlockPlacedByOlderAction() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "place-torch", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:air", "minecraft:redstone_torch"), NOW);
        recordChange(stack, "release-water", "Alex", "project", "minecraft:overworld",
                change(2, "minecraft:grass_block", "minecraft:air"), NOW.plusSeconds(1));

        stack.recordCurrentCausalAction(
                "release-water",
                "Alex",
                "project",
                "minecraft:overworld",
                List.of(change(1, "minecraft:air", "minecraft:water")),
                List.of(),
                NOW.plusSeconds(2)
        );

        StoredBlockChange brokenTorch = changeAt(stack.selectUndo().action(), 1);
        assertEquals("minecraft:redstone_torch", brokenTorch.oldValue().blockId());
        assertEquals("minecraft:water", brokenTorch.newValue().blockId());
    }

    @Test
    void causalFluidDoesNotUseAppliedStateFromAnotherDimension() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "nether-place", "Alex", "project", "minecraft:the_nether",
                change(1, "minecraft:netherrack", "minecraft:redstone_torch"), NOW);
        recordChange(stack, "overworld-release", "Alex", "project", "minecraft:overworld",
                change(2, "minecraft:grass_block", "minecraft:air"), NOW.plusSeconds(1));

        recordCurrentCausalChange(stack,
                "overworld-release",
                "Alex",
                "project",
                "minecraft:overworld",
                change(1, "minecraft:grass_block", "minecraft:water"),
                NOW.plusSeconds(2)
        );

        StoredBlockChange floodedGap = changeAt(stack.selectUndo().action(), 1);
        assertEquals("minecraft:grass_block", floodedGap.oldValue().blockId());
        assertEquals("minecraft:water", floodedGap.newValue().blockId());
    }

    @Test
    void causalBatchDoesNotUseAppliedStateFromAnotherDimension() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "nether-place", "Alex", "project", "minecraft:the_nether",
                change(1, "minecraft:netherrack", "minecraft:redstone_torch"), NOW);
        recordChange(stack, "overworld-release", "Alex", "project", "minecraft:overworld",
                change(2, "minecraft:grass_block", "minecraft:air"), NOW.plusSeconds(1));

        stack.recordCurrentCausalAction(
                "overworld-release",
                "Alex",
                "project",
                "minecraft:overworld",
                List.of(change(1, "minecraft:grass_block", "minecraft:water")),
                List.of(),
                NOW.plusSeconds(2)
        );

        StoredBlockChange floodedGap = changeAt(stack.selectUndo().action(), 1);
        assertEquals("minecraft:grass_block", floodedGap.oldValue().blockId());
        assertEquals("minecraft:water", floodedGap.newValue().blockId());
    }

    @Test
    void causalHiddenChangesStayInUndoRedoPayload() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "place-water", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:air", "minecraft:water"), NOW);

        recordCurrentCausalChange(stack,
                "place-water",
                "Alex",
                "project",
                "minecraft:overworld",
                hiddenChange(2, "minecraft:air", "minecraft:cobblestone"),
                NOW.plusSeconds(1)
        );

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        assertNotNull(selection);
        assertEquals("place-water", selection.action().id());
        assertEquals(2, selection.action().size());
        assertTrue(selection.action().redoChanges().stream().anyMatch(StoredBlockChange::hidden));
        assertTrue(selection.action().inverseChanges().stream().anyMatch(StoredBlockChange::hidden));
    }

    @Test
    void currentCausalChangeStartsNewUndoActionWhenNoRootBlockChanged() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "previous-placement", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:air", "minecraft:oak_sapling"), NOW);

        recordCurrentCausalChange(stack,
                "bonemeal-growth",
                "Alex",
                "project",
                "minecraft:overworld",
                hiddenChange(1, "minecraft:oak_sapling", "minecraft:oak_log"),
                NOW.plusSeconds(1)
        );

        List<UndoRedoAction> recent = stack.recentUndoActions(2);
        assertEquals(List.of("bonemeal-growth", "previous-placement"),
                recent.stream().map(UndoRedoAction::id).toList());
        assertTrue(recent.getFirst().redoChanges().getFirst().hidden());
    }

    @Test
    void currentCausalBatchStartsNewUndoActionWhenNoRootBlockChanged() {
        UndoRedoActionStack stack = new UndoRedoActionStack();

        stack.recordCurrentCausalAction(
                "bonemeal-growth",
                "Alex",
                "project",
                "minecraft:overworld",
                List.of(
                        hiddenChange(1, "minecraft:oak_sapling", "minecraft:oak_log"),
                        hiddenChange(2, "minecraft:air", "minecraft:moss_carpet")
                ),
                List.of(),
                NOW.plusSeconds(1)
        );

        UndoRedoAction action = stack.selectUndo().action();
        assertEquals("bonemeal-growth", action.id());
        assertEquals(2, action.size());
        assertTrue(action.redoChanges().stream().allMatch(StoredBlockChange::hidden));
    }

    @Test
    void currentCausalPiecesMergeExistingActionWithoutPromotion() {
        UndoRedoActionStack stack = new UndoRedoActionStack();

        recordCurrentCausalChange(stack,
                "creeper-blast",
                "Alex",
                "project",
                "minecraft:overworld",
                hiddenChange(1, "minecraft:sand", "minecraft:air"),
                NOW
        );
        recordChange(stack, "latest-placement", "Alex", "project", "minecraft:overworld",
                change(30, "minecraft:air", "minecraft:stone"), NOW.plusSeconds(1));

        stack.recordCurrentCausalAction(
                "creeper-blast",
                "Alex",
                "project",
                "minecraft:overworld",
                List.of(hiddenChange(2, "minecraft:sand", "minecraft:air")),
                List.of(),
                NOW.plusSeconds(2)
        );
        recordCurrentCausalChange(stack,
                "creeper-blast",
                "Alex",
                "project",
                "minecraft:overworld",
                hiddenChange(3, "minecraft:sand", "minecraft:air"),
                NOW.plusSeconds(3)
        );

        List<UndoRedoAction> recent = stack.recentUndoActions(2);
        assertEquals(List.of("latest-placement", "creeper-blast"), recent.stream().map(UndoRedoAction::id).toList());
        assertEquals(3, recent.get(1).redoChanges().size());
    }

    @Test
    void ambientMobChangesWithoutPlayerActionAreNotUndoable() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String dropId = "00000000-0000-0000-0000-000000000021";

        stack.recordCurrentCausalAction(
                "",
                "Alex",
                "project",
                "minecraft:overworld",
                List.of(change(1, "minecraft:stone", "minecraft:air")),
                List.of(new StoredEntityChange(dropId, "minecraft:item", null, entity("minecraft:item", dropId, 1.0D))),
                NOW
        );

        assertFalse(stack.canUndo());
    }

    @Test
    void playerOwnedMobBlastUndoRestoresBlocksAndRemovesDrops() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String dropId = "00000000-0000-0000-0000-000000000022";

        stack.recordCurrentCausalAction(
                "creeper-blast",
                "Alex",
                "project",
                "minecraft:overworld",
                List.of(change(1, "minecraft:stone", "minecraft:air")),
                List.of(new StoredEntityChange(dropId, "minecraft:item", null, entity("minecraft:item", dropId, 1.0D))),
                NOW
        );

        UndoRedoAction action = stack.selectUndo().action();
        assertEquals("creeper-blast", action.id());
        assertEquals("minecraft:stone", action.inverseChanges().getFirst().newValue().blockId());
        assertTrue(action.inverseEntityChanges().getFirst().isRemove());
        assertEquals(dropId, action.inverseEntityChanges().getFirst().entityId());
    }

    @Test
    void causalBatchDoesNotPromoteOlderRedstoneAction() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "redstone-toggle", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:air", "minecraft:lever"), NOW);
        recordChange(stack, "latest-placement", "Alex", "project", "minecraft:overworld",
                change(30, "minecraft:air", "minecraft:stone"), NOW.plusSeconds(1));

        stack.recordCurrentCausalAction(
                "redstone-toggle",
                "Alex",
                "project",
                "minecraft:overworld",
                List.of(change(2, "minecraft:repeater", "minecraft:comparator")),
                List.of(),
                NOW.plusSeconds(2)
        );

        List<UndoRedoAction> recent = stack.recentUndoActions(2);
        assertEquals(List.of("latest-placement", "redstone-toggle"), recent.stream().map(UndoRedoAction::id).toList());
        assertEquals(1, recent.getFirst().size());
        assertEquals(2, recent.get(1).size());
    }

    @Test
    void duplicateReconciledRemovalDoesNotRestoreDestroyedTriggerBlock() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "blast", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:air", "minecraft:redstone_block"), NOW);
        recordChange(stack, "blast", "Alex", "project", "minecraft:overworld",
                change(2, "minecraft:tnt", "minecraft:air"), NOW.plusSeconds(1));
        recordChange(stack, "blast", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:redstone_block", "minecraft:air"), NOW.plusSeconds(2));

        recordCurrentCausalChange(stack,
                "blast",
                "Alex",
                "project",
                "minecraft:overworld",
                change(1, "minecraft:redstone_block", "minecraft:air"),
                NOW.plusSeconds(3)
        );

        UndoRedoAction action = stack.selectUndo().action();
        assertEquals("minecraft:air", changeAt(action, 2).newValue().blockId());
        assertFalse(action.redoChanges().stream().anyMatch(change -> change.pos().x() == 1));
    }

    @Test
    void staleSelectionDoesNotDropUndoHistory() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        recordChange(stack, "action-1", "Alex", "project", "minecraft:overworld", change(1, "minecraft:stone", "minecraft:dirt"), NOW);

        UndoRedoActionStack.Selection staleSelection = stack.selectUndo();
        recordChange(stack, "action-2", "Alex", "project", "minecraft:overworld", change(2, "minecraft:air", "minecraft:oak_planks"), NOW);
        stack.completeUndo(staleSelection);

        assertTrue(stack.canUndo());
        assertFalse(stack.canRedo());
        assertEquals(List.of("action-2", "action-1"), stack.recentUndoActions(2).stream().map(UndoRedoAction::id).toList());
    }

    @Test
    void entityChangesParticipateInUndoRedoActions() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String entityId = "00000000-0000-0000-0000-000000000010";

        recordEntityChange(stack,
                "action-entity",
                "Axiom",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        entity("minecraft:block_display", entityId, 1.0D),
                        entity("minecraft:block_display", entityId, 2.0D)
                ),
                NOW
        );

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        assertNotNull(selection);
        assertEquals(1, selection.action().size());
        StoredEntityChange undo = selection.action().undoEntityChanges().getFirst();
        assertEquals(entityId, undo.entityId());
        assertEquals(2.0D, undo.newValue().entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D));
        assertEquals(1.0D, selection.action().inverseEntityChanges().getFirst().newValue()
                .entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D));
    }

    @Test
    void delayedEntitySpawnKeepsActionSpawnWhenUpdateArrivesFirst() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String entityId = "00000000-0000-0000-0000-000000000013";

        recordEntityChange(stack,
                "action-entity",
                "tester",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(
                        entityId,
                        "minecraft:cow",
                        entity("minecraft:cow", entityId, 1.0D),
                        entity("minecraft:cow", entityId, 2.0D)
                ),
                NOW
        );
        recordEntityChange(stack,
                "action-entity",
                "tester",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(
                        entityId,
                        "minecraft:cow",
                        null,
                        entity("minecraft:cow", entityId, 2.0D)
                ),
                NOW.plusMillis(50)
        );

        UndoRedoActionStack.Selection selection = stack.selectUndo();
        assertNotNull(selection);
        UndoRedoAction action = selection.action();
        StoredEntityChange undo = action.undoEntityChanges().getFirst();
        assertTrue(undo.isSpawn());
        assertTrue(action.inverseEntityChanges().getFirst().isRemove());
        assertEquals(entityId, undo.entityId());
    }

    @Test
    void delayedEntitySpawnBecomesUndoTargetWhenItIsLatestWorldChange() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String entityId = "00000000-0000-0000-0000-000000000014";

        recordChange(stack, "earlier-placement", "Alex", "project", "minecraft:overworld",
                change(1, "minecraft:air", "minecraft:stone"), NOW);
        recordChange(stack, "latest-bridge", "Alex", "project", "minecraft:overworld",
                change(30, "minecraft:air", "minecraft:spruce_planks"), NOW.plusSeconds(2));
        recordDelayedEntityChange(stack,
                "entity-spawn",
                "Alex",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(entityId, "minecraft:item", null, entity("minecraft:item", entityId, 2.0D)),
                NOW.plusSeconds(1),
                NOW.plusSeconds(3)
        );

        List<UndoRedoAction> recent = stack.recentUndoActions(3);
        assertEquals(List.of("latest-bridge", "entity-spawn", "earlier-placement"),
                recent.stream().map(UndoRedoAction::id).toList());
        assertEquals("entity-spawn", stack.selectUndo().action().id());
    }

    @Test
    void delayedEntitySpawnMergesExistingActionWithoutPromotion() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String entityId = "00000000-0000-0000-0000-000000000015";

        recordEntityChange(stack,
                "entity-spawn",
                "Alex",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(
                        entityId,
                        "minecraft:cow",
                        entity("minecraft:cow", entityId, 1.0D),
                        entity("minecraft:cow", entityId, 2.0D)
                ),
                NOW
        );
        recordChange(stack, "latest-bridge", "Alex", "project", "minecraft:overworld",
                change(30, "minecraft:air", "minecraft:spruce_planks"), NOW.plusSeconds(1));
        recordDelayedEntityChange(stack,
                "entity-spawn",
                "Alex",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(entityId, "minecraft:cow", null, entity("minecraft:cow", entityId, 2.0D)),
                NOW,
                NOW.plusSeconds(2)
        );

        List<UndoRedoAction> recent = stack.recentUndoActions(2);
        assertEquals(List.of("latest-bridge", "entity-spawn"), recent.stream().map(UndoRedoAction::id).toList());
        StoredEntityChange stored = recent.get(1).undoEntityChanges().getFirst();
        assertTrue(stored.isSpawn());
        assertTrue(recent.get(1).inverseEntityChanges().getFirst().isRemove());
    }

    @Test
    void batchActionsCanBeUndoneAndRedone() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String entityId = "00000000-0000-0000-0000-000000000012";

        stack.recordAction(
                "partial-restore-action",
                "Alex",
                "project",
                "minecraft:overworld",
                List.of(
                        change(1, "minecraft:stone", "minecraft:glass"),
                        change(2, "minecraft:dirt", "minecraft:gold_block")
                ),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        entity("minecraft:block_display", entityId, 1.0D),
                        entity("minecraft:block_display", entityId, 2.0D)
                )),
                NOW
        );

        UndoRedoActionStack.Selection undo = stack.selectUndo();
        assertNotNull(undo);
        assertEquals(3, undo.action().size());
        assertEquals("partial-restore-action", undo.action().id());

        stack.completeUndo(undo);
        assertFalse(stack.canUndo());
        assertTrue(stack.canRedo());

        stack.completeRedo(stack.selectRedo());
        assertTrue(stack.canUndo());
        assertFalse(stack.canRedo());
    }

    @Test
    void entityDeathAndDropsReplayAsOnePlayerAction() {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String cowId = "00000000-0000-0000-0000-000000000016";
        String dropId = "00000000-0000-0000-0000-000000000017";

        recordDelayedEntityChange(stack,
                "kill-cow",
                "Alex",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(cowId, "minecraft:cow", entity("minecraft:cow", cowId, 1.0D), null),
                NOW,
                NOW
        );
        recordDelayedEntityChange(stack,
                "kill-cow",
                "Alex",
                "project",
                "minecraft:overworld",
                new StoredEntityChange(dropId, "minecraft:item", null, entity("minecraft:item", dropId, 1.0D)),
                NOW,
                NOW.plusMillis(50)
        );

        UndoRedoAction action = stack.selectUndo().action();
        List<StoredEntityChange> undo = action.undoEntityChanges();
        assertEquals(List.of(dropId, cowId), undo.stream().map(StoredEntityChange::entityId).toList());
        assertTrue(undo.get(0).isSpawn());
        assertTrue(undo.get(1).isRemove());

        List<StoredEntityChange> undoApply = action.inverseEntityChanges();
        assertEquals(List.of(dropId, cowId), undoApply.stream().map(StoredEntityChange::entityId).toList());
        assertTrue(undoApply.get(0).isRemove());
        assertTrue(undoApply.get(1).isSpawn());

        List<StoredEntityChange> redo = action.redoEntityChanges();
        assertEquals(List.of(cowId, dropId), redo.stream().map(StoredEntityChange::entityId).toList());
        assertTrue(redo.get(0).isRemove());
        assertTrue(redo.get(1).isSpawn());
    }

    @Test
    void delayedEntityBatchRecordsOneUndoAction() throws Exception {
        UndoRedoActionStack stack = new UndoRedoActionStack();
        String cowId = "00000000-0000-0000-0000-000000000019";
        String pigId = "00000000-0000-0000-0000-000000000020";

        stack.recordDelayedEntityChanges(
                "blast",
                "Alex",
                "project",
                "minecraft:overworld",
                List.of(
                        new StoredEntityChange(cowId, "minecraft:cow", entity("minecraft:cow", cowId, 1.0D), null),
                        new StoredEntityChange(pigId, "minecraft:pig", entity("minecraft:pig", pigId, 2.0D), null)
                ),
                NOW,
                NOW.plusMillis(50)
        );

        UndoRedoAction action = stack.selectUndo().action();
        assertEquals("blast", action.id());
        assertEquals(2, action.size());
        assertEquals(List.of(pigId, cowId), action.inverseEntityChanges().stream()
                .map(StoredEntityChange::entityId)
                .toList());
    }

    private static long recordChange(
            UndoRedoActionStack stack,
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            StoredBlockChange change,
            Instant now
    ) {
        return stack.recordAction(actionId, actor, projectId, dimensionId, List.of(change), List.of(), now);
    }

    private static long recordCurrentCausalChange(
            UndoRedoActionStack stack,
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            StoredBlockChange change,
            Instant now
    ) {
        return stack.recordCurrentCausalAction(actionId, actor, projectId, dimensionId, List.of(change), List.of(), now);
    }

    private static long recordEntityChange(
            UndoRedoActionStack stack,
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            StoredEntityChange change,
            Instant now
    ) {
        return stack.recordAction(actionId, actor, projectId, dimensionId, List.of(), List.of(change), now);
    }

    private static long recordDelayedEntityChange(
            UndoRedoActionStack stack,
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            StoredEntityChange change,
            Instant actionStartedAt,
            Instant now
    ) {
        return stack.recordDelayedEntityChanges(
                actionId,
                actor,
                projectId,
                dimensionId,
                change == null ? List.of() : List.of(change),
                actionStartedAt,
                now
        );
    }

    private static StoredBlockChange change(int x, String oldBlock, String newBlock) {
        return change(x, oldBlock, newBlock, false);
    }

    private static StoredBlockChange hiddenChange(int x, String oldBlock, String newBlock) {
        return change(x, oldBlock, newBlock, true);
    }

    private static StoredBlockChange change(int x, String oldBlock, String newBlock, boolean hidden) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 1),
                new StatePayload(state(oldBlock), null),
                new StatePayload(state(newBlock), null),
                hidden
        );
    }

    private static StoredBlockChange changeAt(UndoRedoAction action, int x) {
        return action.redoChanges().stream()
                .filter(change -> change.pos().x() == x)
                .findFirst()
                .orElseThrow();
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private static EntityPayload entity(String type, String uuid, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
