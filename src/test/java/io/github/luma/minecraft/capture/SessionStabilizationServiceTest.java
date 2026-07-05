package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStabilizationServiceTest {

    @Test
    void reconciliationResultExposesImmutableDeltaChanges() {
        List<StoredBlockChange> deltas = new ArrayList<>();
        deltas.add(changeAt(1));

        SessionStabilizationService.ReconciliationResult result = new SessionStabilizationService.ReconciliationResult(
                1,
                List.of(new ChunkPoint(0, 0)),
                1,
                1,
                0,
                1,
                false,
                true,
                Map.of(),
                deltas
        );

        deltas.clear();

        assertEquals(1, result.deltaChanges().size());
        assertEquals(List.of(new ChunkPoint(0, 0)), result.chunks());
        assertTrue(result.bufferChanged());
        assertThrows(UnsupportedOperationException.class, () -> result.deltaChanges().add(changeAt(2)));
    }

    @Test
    void emptyReconciliationResultsHaveNoDeltaChanges() {
        assertTrue(SessionStabilizationService.ReconciliationResult.noOp().deltaChanges().isEmpty());
        assertTrue(SessionStabilizationService.ReconciliationResult.busy().deltaChanges().isEmpty());
    }

    @Test
    void hiddenDeferredContextMarksChunkDeltasHidden() {
        SessionStabilizationService service = new SessionStabilizationService();
        StoredBlockChange fluidFallout = new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:water"),
                payload("minecraft:cobblestone")
        );
        StoredBlockChange ordinaryMechanism = new StoredBlockChange(
                new BlockPoint(32, 64, 1),
                payload("minecraft:air"),
                payload("minecraft:piston_head")
        );

        List<StoredBlockChange> changes = service.applyDeferredVisibility(
                List.of(fluidFallout, ordinaryMechanism),
                Map.of(
                        new ChunkPoint(0, 0),
                        new CaptureSessionState.DeferredActionContext("fluid-action", "builder", true, true),
                        new ChunkPoint(2, 0),
                        new CaptureSessionState.DeferredActionContext("piston-action", "builder", true, false)
                )
        );

        assertTrue(changes.get(0).hidden());
        assertFalse(changes.get(1).hidden());
    }

    @Test
    void diffChunkKeepsBlockEntityChangesWhenSectionStorageMatches() {
        SessionStabilizationService service = new SessionStabilizationService();
        ChunkSectionSnapshotPayload section = new ChunkSectionSnapshotPayload(
                4,
                List.of(stateTag("minecraft:stone")),
                new long[0],
                0
        );
        int blockEntityIndex = io.github.luma.storage.repository.SnapshotWriter.packVerticalIndex(0, 1, 1);
        ChunkSnapshotPayload baseline = new ChunkSnapshotPayload(
                0,
                0,
                64,
                79,
                List.of(section),
                Map.of(blockEntityIndex, blockEntity("minecraft:chest", "old"))
        );
        ChunkSnapshotPayload live = new ChunkSnapshotPayload(
                0,
                0,
                64,
                79,
                List.of(section),
                Map.of(blockEntityIndex, blockEntity("minecraft:chest", "new"))
        );

        List<StoredBlockChange> changes = service.diffChunk(baseline, live, null);

        assertEquals(1, changes.size());
        assertEquals(new BlockPoint(1, 64, 1), changes.getFirst().pos());
    }

    @Test
    void diffChunkReadsMinecraftPaddedPaletteStorage() {
        SessionStabilizationService service = new SessionStabilizationService();
        short[] logicalIndexes = patternedIndexes();
        List<CompoundTag> baselinePalette = numberedPalette();
        List<CompoundTag> livePalette = new ArrayList<>();
        int[] liveIndexByLogicalState = new int[32];
        for (int liveIndex = 0; liveIndex < 32; liveIndex++) {
            int logicalState = (liveIndex + 5) & 31;
            livePalette.add(baselinePalette.get(logicalState));
            liveIndexByLogicalState[logicalState] = liveIndex;
        }

        short[] liveIndexes = new short[logicalIndexes.length];
        for (int index = 0; index < logicalIndexes.length; index++) {
            liveIndexes[index] = (short) liveIndexByLogicalState[logicalIndexes[index]];
        }
        ChunkSectionSnapshotPayload baselineSection = new ChunkSectionSnapshotPayload(
                0,
                baselinePalette,
                packMinecraftIndexes(logicalIndexes, 5),
                5
        );
        ChunkSectionSnapshotPayload liveSection = new ChunkSectionSnapshotPayload(
                0,
                livePalette,
                packMinecraftIndexes(liveIndexes, 5),
                5
        );
        ChunkSnapshotPayload baseline = new ChunkSnapshotPayload(
                0,
                0,
                0,
                15,
                List.of(baselineSection),
                Map.of()
        );
        ChunkSnapshotPayload live = new ChunkSnapshotPayload(
                0,
                0,
                0,
                15,
                List.of(liveSection),
                Map.of()
        );

        assertArrayEquals(logicalIndexes, baselineSection.unpackPaletteIndexes());
        assertTrue(service.diffChunk(baseline, live, null).isEmpty());
    }

    @Test
    void diffChunkKeepsRedstonePropertyDeltas() {
        assertEquals(4096, this.diffSingleState(
                stateTag("minecraft:redstone_lamp", "lit", "false"),
                stateTag("minecraft:redstone_lamp", "lit", "true")
        ).size());
        assertEquals(4096, this.diffSingleState(
                stateTag("minecraft:lever", "powered", "false"),
                stateTag("minecraft:lever", "powered", "true")
        ).size());
        assertEquals(4096, this.diffSingleState(
                stateTag("minecraft:redstone_wire", "power", "0"),
                stateTag("minecraft:redstone_wire", "power", "15")
        ).size());
    }

    @Test
    void diffChunkUsesBaselineCorrectionWhenDeferredMutationPollutedSnapshot() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint pos = new BlockPoint(1, 64, 1);
        StatePayload trueBaseline = new StatePayload(stateTag("minecraft:redstone_wire", "power", "0"), null);
        ChunkSnapshotPayload pollutedBaseline = chunkWithSingleState(
                pos,
                stateTag("minecraft:air"),
                stateTag("minecraft:redstone_wire", "power", "15")
        );
        ChunkSnapshotPayload live = chunkWithSingleState(
                pos,
                stateTag("minecraft:air"),
                stateTag("minecraft:redstone_wire", "power", "0")
        );

        assertTrue(service.diffChunk(pollutedBaseline, live, null, Map.of(pos, trueBaseline)).isEmpty());
    }

    @Test
    void diffChunkUsesBaselineCorrectionEvenWhenSnapshotMatchesLive() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint pos = new BlockPoint(1, 64, 1);
        StatePayload trueBaseline = new StatePayload(stateTag("minecraft:dispenser", "triggered", "false"), null);
        ChunkSnapshotPayload pollutedBaseline = chunkWithSingleState(
                pos,
                stateTag("minecraft:air"),
                stateTag("minecraft:dispenser", "triggered", "true")
        );
        ChunkSnapshotPayload live = chunkWithSingleState(
                pos,
                stateTag("minecraft:air"),
                stateTag("minecraft:dispenser", "triggered", "true")
        );

        List<StoredBlockChange> changes = service.diffChunk(
                pollutedBaseline,
                live,
                null,
                Map.of(pos, trueBaseline)
        );

        assertEquals(1, changes.size());
        assertEquals(pos, changes.getFirst().pos());
        assertEquals(trueBaseline.stateTag(), changes.getFirst().oldValue().stateTag());
        assertEquals(stateTag("minecraft:dispenser", "triggered", "true"), changes.getFirst().newValue().stateTag());
    }

    @Test
    void diffChunkKeepsStructuralStateDeltas() {
        List<StoredBlockChange> changes = this.diffSingleState(
                stateTag("minecraft:redstone_lamp", "lit", "false"),
                stateTag("minecraft:copper_block")
        );

        assertEquals(4096, changes.size());
    }

    @Test
    void stabilizationUsesSettledRedstoneStateWhenPropertyChanged() {
        SessionStabilizationService service = new SessionStabilizationService();
        StoredBlockChange placedLamp = new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:air"),
                new StatePayload(stateTag("minecraft:redstone_lamp", "lit", "false"), null)
        );
        StoredBlockChange litFallout = new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:air"),
                new StatePayload(stateTag("minecraft:redstone_lamp", "lit", "true"), null)
        );

        List<StoredBlockChange> persistentDeltas = service.persistentDeltaChanges(List.of(placedLamp), List.of(litFallout));

        assertEquals(List.of(litFallout), persistentDeltas);
    }

    @Test
    void diffChunkKeepsSettledPistonDeltas() {
        assertEquals(4096, this.diffSingleState(
                stateTag("minecraft:piston", "extended", "false"),
                stateTag("minecraft:piston", "extended", "true")
        ).size());
        assertEquals(4096, this.diffSingleState(
                stateTag("minecraft:air"),
                stateTag("minecraft:piston_head")
        ).size());
    }

    @Test
    void diffChunkNarrowsWorkToKnownDirtySections() {
        SessionStabilizationService service = new SessionStabilizationService();
        ChunkSnapshotPayload baseline = new ChunkSnapshotPayload(
                0,
                0,
                64,
                95,
                List.of(
                        new ChunkSectionSnapshotPayload(4, List.of(stateTag("minecraft:stone")), new long[0], 0),
                        new ChunkSectionSnapshotPayload(5, List.of(stateTag("minecraft:dirt")), new long[0], 0)
                ),
                Map.of()
        );
        ChunkSnapshotPayload live = new ChunkSnapshotPayload(
                0,
                0,
                64,
                95,
                List.of(
                        new ChunkSectionSnapshotPayload(4, List.of(stateTag("minecraft:gold_block")), new long[0], 0),
                        new ChunkSectionSnapshotPayload(5, List.of(stateTag("minecraft:diamond_block")), new long[0], 0)
                ),
                Map.of()
        );

        List<StoredBlockChange> changes = service.diffChunk(baseline, live, null, Map.of(), Set.of(5));

        assertEquals(4096, changes.size());
        assertTrue(changes.stream().allMatch(change -> change.pos().y() >= 80 && change.pos().y() <= 95));
    }

    @Test
    void baselineCorrectionOutsideCandidateSectionsDoesNotPullUnrelatedCells() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint correctedPos = new BlockPoint(1, 64, 1);
        BlockPoint unrelatedPos = new BlockPoint(2, 64, 1);
        ChunkSnapshotPayload baseline = uniformChunk("minecraft:air");
        ChunkSnapshotPayload live = chunkWithStates(
                stateTag("minecraft:air"),
                Map.of(unrelatedPos, stateTag("minecraft:gold_block"))
        );

        List<StoredBlockChange> changes = service.diffChunk(
                baseline,
                live,
                null,
                Map.of(correctedPos, payload("minecraft:air")),
                Set.of(5)
        );

        assertTrue(changes.isEmpty());
    }

    @Test
    void diffChunkKeepsPistonMovedSourceAndDestinationFromPreMotionBaseline() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint source = new BlockPoint(1, 64, 1);
        BlockPoint destination = new BlockPoint(2, 64, 1);
        ChunkSnapshotPayload baseline = chunkWithStates(
                stateTag("minecraft:air"),
                Map.of(source, stateTag("minecraft:oak_planks"))
        );
        ChunkSnapshotPayload live = chunkWithStates(
                stateTag("minecraft:air"),
                Map.of(destination, stateTag("minecraft:oak_planks"))
        );

        List<StoredBlockChange> changes = service.diffChunk(baseline, live, null);

        assertEquals(2, changes.size());
        Map<BlockPoint, StoredBlockChange> byPos = new java.util.HashMap<>();
        for (StoredBlockChange change : changes) {
            byPos.put(change.pos(), change);
        }
        assertEquals("minecraft:oak_planks", byPos.get(source).oldValue().blockId());
        assertEquals("minecraft:air", byPos.get(source).newValue().blockId());
        assertEquals("minecraft:air", byPos.get(destination).oldValue().blockId());
        assertEquals("minecraft:oak_planks", byPos.get(destination).newValue().blockId());
    }

    @Test
    void baselineCorrectionDropsPistonDoorRoundTripFromPollutedChunkBaseline() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint source = new BlockPoint(1, 64, 1);
        BlockPoint destination = new BlockPoint(2, 64, 1);
        StatePayload air = payload("minecraft:air");
        StatePayload movedBlock = payload("minecraft:oak_planks");
        List<StoredBlockChange> openDoorChanges = List.of(
                new StoredBlockChange(source, movedBlock, air),
                new StoredBlockChange(destination, air, movedBlock)
        );
        ChunkSnapshotPayload pollutedBaseline = uniformChunk("minecraft:air");
        ChunkSnapshotPayload liveClosed = chunkWithStates(
                stateTag("minecraft:air"),
                Map.of(source, stateTag("minecraft:oak_planks"))
        );

        List<StoredBlockChange> settledDeltas = service.diffChunk(
                pollutedBaseline,
                liveClosed,
                null,
                Map.of(source, movedBlock)
        );

        assertTrue(settledDeltas.isEmpty());
        assertTrue(service.composeStabilizedChanges(
                List.of(),
                openDoorChanges,
                settledDeltas,
                Map.of(new ChunkPoint(0, 0), liveClosed)
        ).isEmpty());
    }

    @Test
    void stabilizationCompositionRemovesCurrentChangesThatSettledBackToBaseline() {
        SessionStabilizationService service = new SessionStabilizationService();
        StoredBlockChange placedThenMovedBlock = new StoredBlockChange(
                new BlockPoint(2, 64, 1),
                payload("minecraft:air"),
                payload("minecraft:oak_planks")
        );

        assertTrue(service.composeStabilizedChanges(
                List.of(),
                List.of(placedThenMovedBlock),
                List.of()
        ).isEmpty());
    }

    @Test
    void stabilizationCompositionKeepsCurrentChangeWhenLateBaselineAlreadyContainsTarget() {
        SessionStabilizationService service = new SessionStabilizationService();
        StoredBlockChange directBreak = new StoredBlockChange(
                new BlockPoint(5, 64, 5),
                payload("minecraft:grass_block"),
                payload("minecraft:air")
        );

        List<StoredBlockChange> composedChanges = service.composeStabilizedChanges(
                List.of(),
                List.of(directBreak),
                List.of(),
                Map.of(new ChunkPoint(0, 0), uniformChunk("minecraft:air"))
        );

        assertEquals(List.of(directBreak), composedChanges);
    }

    @Test
    void stabilizationCompositionPreservesDirectCaptureBaselineWhenDeltaReplacesSamePosition() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint pos = new BlockPoint(2, 64, 1);
        StoredBlockChange directPlacement = new StoredBlockChange(
                pos,
                payload("minecraft:air"),
                payload("minecraft:short_grass")
        );
        StoredBlockChange settledDelta = new StoredBlockChange(
                pos,
                payload("minecraft:stone"),
                payload("minecraft:tall_grass")
        );

        List<StoredBlockChange> composedChanges = service.composeStabilizedChanges(
                List.of(),
                List.of(directPlacement),
                List.of(settledDelta),
                Map.of(ChunkPoint.from(pos), chunkWithStates(
                        stateTag("minecraft:stone"),
                        Map.of(pos, stateTag("minecraft:tall_grass"))
                ))
        );

        assertEquals(1, composedChanges.size());
        assertEquals(pos, composedChanges.getFirst().pos());
        assertEquals("minecraft:air", composedChanges.getFirst().oldValue().blockId());
        assertEquals("minecraft:tall_grass", composedChanges.getFirst().newValue().blockId());
    }

    @Test
    void finalLiveDiffAddsChangedCellsMissingFromDirectDraft() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint capturedPos = new BlockPoint(2, 64, 1);
        BlockPoint missedPos = new BlockPoint(3, 64, 1);
        StoredBlockChange directDraftChange = new StoredBlockChange(
                capturedPos,
                payload("minecraft:air"),
                payload("minecraft:oak_planks")
        );
        ChunkSnapshotPayload baseline = uniformChunk("minecraft:air");
        ChunkSnapshotPayload live = chunkWithStates(
                stateTag("minecraft:air"),
                Map.of(
                        capturedPos, stateTag("minecraft:oak_planks"),
                        missedPos, stateTag("minecraft:stone")
                )
        );

        List<StoredBlockChange> liveDeltas = service.diffChunk(baseline, live, null, Map.of(), Set.of(4));
        List<StoredBlockChange> composedChanges = service.composeStabilizedChanges(
                List.of(),
                List.of(directDraftChange),
                liveDeltas,
                Map.of(new ChunkPoint(0, 0), live)
        );

        Map<BlockPoint, StoredBlockChange> byPosition = new java.util.HashMap<>();
        for (StoredBlockChange change : composedChanges) {
            byPosition.put(change.pos(), change);
        }
        assertEquals(2, composedChanges.size());
        assertEquals("minecraft:oak_planks", byPosition.get(capturedPos).newValue().blockId());
        assertEquals("minecraft:stone", byPosition.get(missedPos).newValue().blockId());
    }

    @Test
    void stabilizationCompositionDropsCurrentChangeWhenLiveStateReturnedToBaseline() {
        SessionStabilizationService service = new SessionStabilizationService();
        StoredBlockChange movedSource = new StoredBlockChange(
                new BlockPoint(2, 64, 1),
                payload("minecraft:air"),
                payload("minecraft:oak_planks")
        );

        assertTrue(service.composeStabilizedChanges(
                List.of(),
                List.of(movedSource),
                List.of(),
                Map.of(new ChunkPoint(0, 0), uniformChunk("minecraft:air"))
        ).isEmpty());
    }

    @Test
    void reconciliationRelatedActionChangesRecordCurrentChangeThatReturnedToBaseline() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint pos = new BlockPoint(2, 64, 1);
        StatePayload air = payload("minecraft:air");
        StatePayload movedObserver = payload("minecraft:observer");
        StoredBlockChange liftedBlock = new StoredBlockChange(pos, air, movedObserver);

        List<StoredBlockChange> related = service.relatedActionChanges(
                List.of(liftedBlock),
                List.of(),
                Map.of(new ChunkPoint(0, 0), uniformChunk("minecraft:air"))
        );

        assertEquals(1, related.size());
        assertEquals(pos, related.getFirst().pos());
        assertEquals(movedObserver, related.getFirst().oldValue());
        assertEquals(air, related.getFirst().newValue());
    }

    @Test
    void reconciliationActionChangesRecordDraftTransitionBackToBaseline() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint pos = new BlockPoint(2, 64, 1);
        StatePayload air = payload("minecraft:air");
        StatePayload movedBlock = payload("minecraft:gray_concrete");
        StoredBlockChange openDoorState = new StoredBlockChange(pos, air, movedBlock);

        List<StoredBlockChange> actionChanges = service.reconciliationActionChanges(
                List.of(openDoorState),
                List.of()
        );

        assertEquals(1, actionChanges.size());
        assertEquals(pos, actionChanges.getFirst().pos());
        assertEquals(movedBlock, actionChanges.getFirst().oldValue());
        assertEquals(air, actionChanges.getFirst().newValue());
    }

    @Test
    void reconciliationActionChangesRecordDraftTransitionToSettledMechanismState() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint pos = new BlockPoint(2, 64, 1);
        StatePayload air = payload("minecraft:air");
        StatePayload movedBlock = payload("minecraft:gray_concrete");
        StoredBlockChange settledDoorState = new StoredBlockChange(pos, air, movedBlock);

        List<StoredBlockChange> actionChanges = service.reconciliationActionChanges(
                List.of(),
                List.of(settledDoorState)
        );

        assertEquals(List.of(settledDoorState), actionChanges);
    }

    @Test
    void hiddenDeferredReconciliationUsesComposedChangesForLiveUndoPayload() {
        SessionStabilizationService service = new SessionStabilizationService();
        StoredBlockChange settledGrowth = new StoredBlockChange(
                new BlockPoint(2, 64, 1),
                payload("minecraft:oak_sapling"),
                payload("minecraft:oak_log"),
                true
        );

        List<StoredBlockChange> actionChanges = service.reconciliationActionChanges(
                List.of(settledGrowth),
                List.of(settledGrowth),
                Map.of(
                        new ChunkPoint(0, 0),
                        new CaptureSessionState.DeferredActionContext("bonemeal-growth", "Alex", true, true)
                )
        );

        assertEquals(List.of(settledGrowth), actionChanges);
    }

    @Test
    void hiddenDeferredFluidUsesDraftTargetAsUndoBaseline() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint pos = new BlockPoint(2, 64, 1);
        StoredBlockChange placedRedstone = new StoredBlockChange(
                pos,
                payload("minecraft:stone"),
                payload("minecraft:redstone_wire")
        );
        StoredBlockChange floodedRedstone = new StoredBlockChange(
                pos,
                payload("minecraft:stone"),
                payload("minecraft:water"),
                true
        );

        List<StoredBlockChange> actionChanges = service.reconciliationActionChanges(
                List.of(placedRedstone),
                List.of(floodedRedstone),
                Map.of(
                        new ChunkPoint(0, 0),
                        new CaptureSessionState.DeferredActionContext("release-water", "Alex", true, true)
                )
        );

        assertEquals(1, actionChanges.size());
        assertEquals(pos, actionChanges.getFirst().pos());
        assertEquals("minecraft:redstone_wire", actionChanges.getFirst().oldValue().blockId());
        assertEquals("minecraft:water", actionChanges.getFirst().newValue().blockId());
    }

    @Test
    void deferredActionPayloadExcludesStartingDraftChangesInSameChunk() {
        SessionStabilizationService service = new SessionStabilizationService();
        StoredBlockChange oldFloorCut = new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:sandstone"),
                payload("minecraft:air")
        );
        StoredBlockChange directWater = new StoredBlockChange(
                new BlockPoint(2, 64, 1),
                payload("minecraft:air"),
                payload("minecraft:water")
        );

        List<StoredBlockChange> actionChanges = service.reconciliationActionChanges(
                List.of(oldFloorCut),
                List.of(directWater),
                List.of(oldFloorCut, directWater),
                Map.of(new ChunkPoint(0, 0),
                        new CaptureSessionState.DeferredActionContext("release-water", "Alex", true))
        );

        assertTrue(actionChanges.isEmpty());
    }

    @Test
    void hiddenDeferredActionPayloadExcludesVisibleStartingDraftChangesInSameChunk() {
        SessionStabilizationService service = new SessionStabilizationService();
        StoredBlockChange oldFloorCut = new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:sandstone"),
                payload("minecraft:air")
        );
        StoredBlockChange floodedRedstone = new StoredBlockChange(
                new BlockPoint(2, 64, 1),
                payload("minecraft:redstone_wire"),
                payload("minecraft:water"),
                true
        );

        List<StoredBlockChange> actionChanges = service.reconciliationActionChanges(
                List.of(oldFloorCut),
                List.of(floodedRedstone),
                List.of(oldFloorCut, floodedRedstone),
                Map.of(new ChunkPoint(0, 0),
                        new CaptureSessionState.DeferredActionContext("release-water", "Alex", true, true))
        );

        assertEquals(List.of(floodedRedstone), actionChanges);
    }

    @Test
    void reconciliationRelatedDeltasExcludeAlreadyTrackedDirectChanges() {
        SessionStabilizationService service = new SessionStabilizationService();
        StoredBlockChange directPlacement = new StoredBlockChange(
                new BlockPoint(2, 64, 1),
                payload("minecraft:air"),
                payload("minecraft:oak_planks")
        );
        StoredBlockChange secondaryPlacement = new StoredBlockChange(
                new BlockPoint(3, 64, 1),
                payload("minecraft:air"),
                payload("minecraft:oak_planks")
        );

        assertEquals(
                List.of(secondaryPlacement),
                service.relatedDeltaChanges(List.of(directPlacement), List.of(directPlacement, secondaryPlacement))
        );
    }

    @Test
    void reconciliationRelatedDeltasUseTrackedCurrentStateAsUndoBaseline() {
        SessionStabilizationService service = new SessionStabilizationService();
        BlockPoint pos = new BlockPoint(2, 64, 1);
        StatePayload placedPiston = new StatePayload(stateTag("minecraft:piston", "extended", "false"), null);
        StatePayload extendedPiston = new StatePayload(stateTag("minecraft:piston", "extended", "true"), null);
        StoredBlockChange directPlacement = new StoredBlockChange(
                pos,
                payload("minecraft:air"),
                placedPiston
        );
        StoredBlockChange settledPiston = new StoredBlockChange(
                pos,
                payload("minecraft:air"),
                extendedPiston
        );

        List<StoredBlockChange> related = service.relatedDeltaChanges(
                List.of(directPlacement),
                List.of(settledPiston)
        );

        assertEquals(1, related.size());
        assertEquals(pos, related.getFirst().pos());
        assertEquals(placedPiston, related.getFirst().oldValue());
        assertEquals(extendedPiston, related.getFirst().newValue());
    }

    private static StoredBlockChange changeAt(int x) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 0),
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        );
    }

    private static StatePayload payload(String blockId) {
        return new StatePayload(stateTag(blockId), null);
    }

    private static CompoundTag stateTag(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return state;
    }

    private static CompoundTag stateTag(String blockId, String propertyName, String propertyValue) {
        CompoundTag state = stateTag(blockId);
        CompoundTag properties = new CompoundTag();
        properties.putString(propertyName, propertyValue);
        state.put("Properties", properties);
        return state;
    }

    private List<StoredBlockChange> diffSingleState(CompoundTag baselineState, CompoundTag liveState) {
        SessionStabilizationService service = new SessionStabilizationService();
        ChunkSnapshotPayload baseline = new ChunkSnapshotPayload(
                0,
                0,
                64,
                79,
                List.of(new ChunkSectionSnapshotPayload(4, List.of(baselineState), new long[0], 0)),
                Map.of()
        );
        ChunkSnapshotPayload live = new ChunkSnapshotPayload(
                0,
                0,
                64,
                79,
                List.of(new ChunkSectionSnapshotPayload(4, List.of(liveState), new long[0], 0)),
                Map.of()
        );
        return service.diffChunk(baseline, live, null);
    }

    private static ChunkSnapshotPayload uniformChunk(String blockId) {
        return new ChunkSnapshotPayload(
                0,
                0,
                64,
                79,
                List.of(new ChunkSectionSnapshotPayload(4, List.of(stateTag(blockId)), new long[0], 0)),
                Map.of()
        );
    }

    private static ChunkSnapshotPayload chunkWithSingleState(
            BlockPoint pos,
            CompoundTag defaultState,
            CompoundTag specialState
    ) {
        short[] indexes = new short[4096];
        indexes[ChunkSectionSnapshotPayload.localIndex(pos.x() & 15, pos.y() & 15, pos.z() & 15)] = 1;
        return new ChunkSnapshotPayload(
                pos.x() >> 4,
                pos.z() >> 4,
                64,
                79,
                List.of(new ChunkSectionSnapshotPayload(
                        pos.y() >> 4,
                        List.of(defaultState, specialState),
                        packMinecraftIndexes(indexes, 1),
                        1
                )),
                Map.of()
        );
    }

    private static ChunkSnapshotPayload chunkWithStates(
            CompoundTag defaultState,
            Map<BlockPoint, CompoundTag> states
    ) {
        short[] indexes = new short[4096];
        List<CompoundTag> palette = new ArrayList<>();
        palette.add(defaultState);
        int chunkX = 0;
        int chunkZ = 0;
        for (Map.Entry<BlockPoint, CompoundTag> entry : states.entrySet()) {
            palette.add(entry.getValue());
            BlockPoint pos = entry.getKey();
            chunkX = pos.x() >> 4;
            chunkZ = pos.z() >> 4;
            indexes[ChunkSectionSnapshotPayload.localIndex(pos.x() & 15, pos.y() & 15, pos.z() & 15)] =
                    (short) (palette.size() - 1);
        }
        int bitsPerEntry = palette.size() <= 2 ? 1 : 2;
        return new ChunkSnapshotPayload(
                chunkX,
                chunkZ,
                64,
                79,
                List.of(new ChunkSectionSnapshotPayload(
                        4,
                        palette,
                        packMinecraftIndexes(indexes, bitsPerEntry),
                        bitsPerEntry
                )),
                Map.of()
        );
    }

    private static CompoundTag blockEntity(String id, String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("marker", marker);
        return tag;
    }

    private static List<CompoundTag> numberedPalette() {
        List<CompoundTag> palette = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            palette.add(stateTag("minecraft:lumi_state_" + index));
        }
        return palette;
    }

    private static short[] patternedIndexes() {
        short[] indexes = new short[4096];
        for (int index = 0; index < indexes.length; index++) {
            indexes[index] = (short) ((index * 7 + 3) & 31);
        }
        return indexes;
    }

    private static long[] packMinecraftIndexes(short[] indexes, int bitsPerEntry) {
        int valuesPerLong = Long.SIZE / bitsPerEntry;
        long[] packed = new long[(indexes.length + valuesPerLong - 1) / valuesPerLong];
        long mask = (1L << bitsPerEntry) - 1L;
        for (int index = 0; index < indexes.length; index++) {
            long value = indexes[index] & mask;
            int storageIndex = index / valuesPerLong;
            int bitOffset = (index - storageIndex * valuesPerLong) * bitsPerEntry;
            packed[storageIndex] |= value << bitOffset;
        }
        return packed;
    }
}
