package io.github.luma.minecraft.world;

import io.github.luma.domain.model.SectionChangeMask;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PatchSectionFrame;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.luma.minecraft.world.WorldChangeBatchPreparer.ProgressListener.NO_OP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldChangeBatchPreparerTest {

    private final WorldChangeBatchPreparer preparer = new WorldChangeBatchPreparer();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preparesEntityOnlyNewValueBatches() throws Exception {
        String entityId = "00000000-0000-0000-0000-000000000001";

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:block_display", null, entity(entityId, 32.0D))),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        assertEquals(1, batches.getFirst().entityBatch().entitiesToSpawn().size());
        assertEquals(2, batches.getFirst().chunk().x());
        assertEquals(false, batches.getFirst().entityBatch().replaceEntities());
    }

    @Test
    void preparesOldValueEntityBatchesByInvertingChanges() throws Exception {
        String entityId = "00000000-0000-0000-0000-000000000002";

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:block_display", null, entity(entityId, 1.0D))),
                false,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        assertEquals(List.of(entityId), batches.getFirst().entityBatch().entityIdsToRemove());
    }

    @Test
    void replaceEntityApplyModeMarksChunkBatchAuthoritative() throws Exception {
        String entityId = "00000000-0000-0000-0000-000000000022";

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:block_display", null, entity(entityId, 32.0D))),
                true,
                NO_OP,
                EntityApplyMode.REPLACE_ENTITIES_IN_CHUNK
        );

        assertEquals(1, batches.size());
        assertEquals(true, batches.getFirst().entityBatch().replaceEntities());
    }

    @Test
    void undoRedoEntityMovementPreparesDeltaUpdateWithoutRemoval() throws Exception {
        String entityId = "00000000-0000-0000-0000-000000000023";

        List<PreparedChunkBatch> batches = this.preparer.prepareUndoRedo(
                null,
                List.of(),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:minecart",
                        entity(entityId, 1.0D),
                        entity(entityId, 2.0D)
                )),
                false,
                null,
                EntityApplyMode.DELTA
        );

        EntityBatch entityBatch = batches.getFirst().entityBatch();
        assertEquals(0, entityBatch.entityIdsToRemove().size());
        assertEquals(0, entityBatch.entitiesToSpawn().size());
        assertEquals(1, entityBatch.entitiesToUpdate().size());
        assertEquals(1.0D, entityBatch.entitiesToUpdate().getFirst()
                .getListOrEmpty("Pos").getDoubleOr(0, 0.0D));
    }

    @Test
    void redoSkipsPrimedTntSpawnButUndoCanRemoveIt() throws Exception {
        String entityId = "00000000-0000-0000-0000-000000000024";
        List<StoredEntityChange> changes = List.of(new StoredEntityChange(
                entityId,
                "minecraft:tnt",
                null,
                entity("minecraft:tnt", entityId, 1.0D)
        ));

        List<PreparedChunkBatch> redoBatches = this.preparer.prepareUndoRedo(
                null,
                List.of(),
                changes,
                true,
                null,
                EntityApplyMode.DELTA
        );
        List<PreparedChunkBatch> undoBatches = this.preparer.prepareUndoRedo(
                null,
                List.of(),
                changes,
                false,
                null,
                EntityApplyMode.DELTA
        );

        assertTrue(redoBatches.isEmpty());
        assertEquals(List.of(entityId), undoBatches.getFirst().entityBatch().entityIdsToRemove());
    }

    @Test
    void undoRedoReplayClearsDeadAndIgnitedEntityPayloadsButKeepsMotion() throws Exception {
        String entityId = "00000000-0000-0000-0000-000000000025";
        CompoundTag tag = entity("minecraft:creeper", entityId, 1.0D).copyTag();
        tag.putShort("DeathTime", (short) 18);
        tag.putShort("HurtTime", (short) 9);
        tag.putShort("Fire", (short) 120);
        tag.putFloat("Health", 0.0F);
        tag.putBoolean("ignited", true);
        tag.putString("Motion", "keep");

        List<PreparedChunkBatch> batches = this.preparer.prepareUndoRedo(
                null,
                List.of(),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:creeper",
                        new EntityPayload(tag),
                        null
                )),
                false,
                null,
                EntityApplyMode.DELTA
        );

        CompoundTag replayTag = batches.getFirst().entityBatch().entitiesToSpawn().getFirst();
        assertEquals(0, replayTag.getShortOr("DeathTime", (short) 0));
        assertEquals(0, replayTag.getShortOr("HurtTime", (short) 0));
        assertEquals(0, replayTag.getShortOr("Fire", (short) 0));
        assertEquals(1.0F, replayTag.getFloatOr("Health", 0.0F));
        assertFalse(replayTag.getBooleanOr("ignited", false));
        assertEquals("keep", replayTag.getString("Motion").orElse(""));
    }

    @Test
    void decodedDenseSectionsUseNativeSectionBatches() {
        List<PreparedBlockPlacement> placements = java.util.stream.IntStream
                .range(0, SectionApplySafetyClassifier.NATIVE_DENSE_THRESHOLD)
                .mapToObj(index -> new PreparedBlockPlacement(
                        new BlockPos(index & 15, 64 + ((index >>> 8) & 15), (index >>> 4) & 15),
                        Blocks.STONE.defaultBlockState(),
                        null
                ))
                .toList();

        PreparedChunkBatch batch = this.preparer.prepareDecodedChunk(new ChunkPoint(0, 0), placements, EntityBatch.empty());

        assertEquals(0, batch.placements().size());
        assertEquals(1, batch.nativeSections().size());
        assertEquals(SectionApplyPath.SECTION_NATIVE, batch.nativeSections().getFirst().safetyProfile().path());
    }

    @Test
    void decodedRewriteSectionsStayNativeInsteadOfFlatteningToPlacements() {
        List<PreparedBlockPlacement> placements = java.util.stream.IntStream
                .range(0, SectionApplySafetyClassifier.CONTAINER_REWRITE_THRESHOLD)
                .mapToObj(index -> new PreparedBlockPlacement(
                        new BlockPos(index & 15, 64 + ((index >>> 8) & 15), (index >>> 4) & 15),
                        Blocks.STONE.defaultBlockState(),
                        null
                ))
                .toList();

        PreparedChunkBatch batch = this.preparer.prepareDecodedChunk(new ChunkPoint(0, 0), placements, EntityBatch.empty());

        assertEquals(0, batch.placements().size());
        assertEquals(1, batch.nativeSections().size());
        assertEquals(SectionApplyPath.SECTION_REWRITE, batch.nativeSections().getFirst().safetyProfile().path());
    }

    @Test
    void decodedSingleLayerSectionsUseRewriteForLargeSurfaceActions() {
        List<PreparedBlockPlacement> placements = java.util.stream.IntStream
                .range(0, 16 * 16)
                .mapToObj(index -> new PreparedBlockPlacement(
                        new BlockPos(index & 15, 64, (index >>> 4) & 15),
                        Blocks.STONE.defaultBlockState(),
                        null
                ))
                .toList();

        PreparedChunkBatch batch = this.preparer.prepareDecodedChunk(new ChunkPoint(0, 0), placements, EntityBatch.empty());

        assertEquals(0, batch.placements().size());
        assertEquals(1, batch.nativeSections().size());
        assertEquals(SectionApplyPath.SECTION_REWRITE, batch.nativeSections().getFirst().safetyProfile().path());
    }

    @Test
    void undoRedoLargeSimpleSectionsPrepareAsRewriteBatches() throws Exception {
        List<StoredBlockChange> changes = java.util.stream.IntStream
                .range(0, SectionApplySafetyClassifier.CONTAINER_REWRITE_THRESHOLD)
                .mapToObj(index -> new StoredBlockChange(
                        new BlockPoint(index & 15, 64 + ((index >>> 8) & 15), (index >>> 4) & 15),
                        payload(Blocks.AIR.defaultBlockState()),
                        payload(Blocks.STONE.defaultBlockState())
                ))
                .toList();

        List<PreparedChunkBatch> batches = this.preparer.prepareUndoRedo(
                null,
                changes,
                List.of(),
                true,
                null,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        assertEquals(0, batches.getFirst().placements().size());
        assertEquals(1, batches.getFirst().nativeSections().size());
        assertEquals(SectionApplyPath.SECTION_REWRITE, batches.getFirst().nativeSections().getFirst().safetyProfile().path());
    }

    @Test
    void sectionFramesKeepRewriteBatchesForFullSections() throws Exception {
        int changedCells = SectionChangeMask.ENTRY_COUNT;
        int[] oldStateIds = new int[changedCells];
        int[] newStateIds = new int[changedCells];
        int[] blockEntityIds = new int[changedCells];
        Arrays.fill(blockEntityIds, -1);

        PatchSectionFrame frame = new PatchSectionFrame(
                0,
                0,
                4,
                mask(changedCells),
                List.of(stateTag("minecraft:air")),
                List.of(stateTag("minecraft:stone")),
                oldStateIds,
                newStateIds,
                List.of(),
                List.of(),
                blockEntityIds,
                blockEntityIds
        );

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                new PatchSectionWorldChanges(List.of(frame), List.of()),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        assertEquals(0, batches.getFirst().placements().size());
        assertEquals(1, batches.getFirst().nativeSections().size());
        assertEquals(SectionApplyPath.SECTION_REWRITE, batches.getFirst().nativeSections().getFirst().safetyProfile().path());
    }

    @Test
    void decodedSparseSectionsStayAsDirectPlacementsBelowNativeCutoff() {
        List<PreparedBlockPlacement> placements = java.util.stream.IntStream
                .range(0, SectionApplySafetyClassifier.NATIVE_DENSE_THRESHOLD - 1)
                .mapToObj(index -> new PreparedBlockPlacement(
                        new BlockPos(index & 15, 64 + ((index >>> 8) & 15), (index >>> 4) & 15),
                        Blocks.STONE.defaultBlockState(),
                        null
                ))
                .toList();

        PreparedChunkBatch batch = this.preparer.prepareDecodedChunk(new ChunkPoint(0, 0), placements, EntityBatch.empty());

        assertEquals(placements.size(), batch.placements().size());
        assertEquals(0, batch.nativeSections().size());
    }

    @Test
    void mechanismRemovalReplayHintForcesFinalReplayAndSuppressesCallbacks() {
        PreparedBlockPlacement.ReplayHint hint = WorldChangeBatchPreparer.replayHintFor(
                Blocks.REDSTONE_TORCH.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        );

        assertTrue(hint.forcesFinalReplay());
        assertTrue(hint.suppressesPostReplayMechanism());
        assertTrue(hint.suppressesPostReplayFluid());
    }

    @Test
    void mechanismTargetReplayHintSuppressesStaleCallbacks() {
        PreparedBlockPlacement.ReplayHint hint = WorldChangeBatchPreparer.replayHintFor(
                Blocks.AIR.defaultBlockState(),
                Blocks.COMPARATOR.defaultBlockState()
        );

        assertTrue(hint.suppressesPostReplayMechanism());
        assertTrue(hint.suppressesPostReplayFluid());
    }

    @Test
    void ordinaryStoneReplayHintStaysPlain() {
        PreparedBlockPlacement.ReplayHint hint = WorldChangeBatchPreparer.replayHintFor(
                Blocks.DIRT.defaultBlockState(),
                Blocks.STONE.defaultBlockState()
        );

        assertFalse(hint.forcesFinalReplay());
        assertFalse(hint.suppressesPostReplayMechanism());
        assertFalse(hint.suppressesPostReplayFluid());
    }

    @Test
    void waterWashableDryBlocksSuppressFluidReplay() {
        List<BlockState> washableStates = List.of(
                Blocks.TORCH.defaultBlockState(),
                Blocks.POPPY.defaultBlockState(),
                Blocks.OAK_SAPLING.defaultBlockState(),
                Blocks.SNOW.defaultBlockState(),
                Blocks.WHITE_CARPET.defaultBlockState(),
                Blocks.REDSTONE_WIRE.defaultBlockState(),
                Blocks.REPEATER.defaultBlockState(),
                Blocks.COBWEB.defaultBlockState(),
                Blocks.BAMBOO_SAPLING.defaultBlockState(),
                Blocks.END_ROD.defaultBlockState(),
                Blocks.SKELETON_SKULL.defaultBlockState(),
                Blocks.FLOWER_POT.defaultBlockState()
        );

        for (BlockState washableState : washableStates) {
            assertTrue(
                    WorldChangeBatchPreparer.replayHintFor(
                            washableState,
                            Blocks.AIR.defaultBlockState()
                    ).suppressesPostReplayFluid(),
                    "removing " + washableState
            );
            assertTrue(
                    WorldChangeBatchPreparer.replayHintFor(
                            Blocks.AIR.defaultBlockState(),
                            washableState
                    ).suppressesPostReplayFluid(),
                    "restoring " + washableState
            );
        }
    }

    @Test
    void undoRedoWaterBrokenDoublePlantGuardsBothHalvesAgainstFluidReplay() throws Exception {
        BlockPos lower = new BlockPos(1, 64, 1);
        BlockState lowerPlant = Blocks.SUNFLOWER.defaultBlockState()
                .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upperPlant = Blocks.SUNFLOWER.defaultBlockState()
                .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);

        List<PreparedChunkBatch> batches = this.preparer.prepareUndoRedo(
                null,
                List.of(
                        new StoredBlockChange(
                                BlockPoint.from(lower),
                                payload(lowerPlant),
                                payload(Blocks.WATER.defaultBlockState()),
                                true
                        ),
                        new StoredBlockChange(
                                BlockPoint.from(lower.above()),
                                payload(upperPlant),
                                payload(Blocks.AIR.defaultBlockState()),
                                true
                        )
                ),
                List.of(),
                false,
                null,
                EntityApplyMode.DELTA
        );

        PreparedBlockPlacement lowerPlacement = placementAt(batches.getFirst(), lower);
        PreparedBlockPlacement upperPlacement = placementAt(batches.getFirst(), lower.above());

        assertTrue(lowerPlacement.replayHint().suppressesPostReplayFluid());
        assertTrue(upperPlacement.replayHint().suppressesPostReplayFluid());
    }

    @Test
    void analyzedPrepareCollectsBoundedMechanismScope() throws Exception {
        BlockPoint pos = new BlockPoint(2, 64, 3);

        PreparedWorldChangeBatches analyzed = this.preparer.prepareAnalyzed(
                null,
                List.of(new StoredBlockChange(
                        pos,
                        payload(Blocks.REDSTONE_WIRE.defaultBlockState()),
                        payload(Blocks.AIR.defaultBlockState())
                )),
                List.of(),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, analyzed.batches().size());
        assertTrue(analyzed.mechanismReplayScope().positions().contains(pos));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(2, 63, 3)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(2, 65, 3)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(3, 64, 3)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(1, 64, 3)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(2, 64, 4)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(2, 64, 2)));
        assertTrue(analyzed.mechanismReplayScope().sections().contains(new ChunkSectionPoint(0, 0, 4)));
    }

    @Test
    void analyzedPrepareCollectsMechanismScopeForRedstonePowerBlocks() throws Exception {
        BlockPoint pos = new BlockPoint(2, 64, 3);

        PreparedWorldChangeBatches analyzed = this.preparer.prepareAnalyzed(
                null,
                List.of(new StoredBlockChange(
                        pos,
                        payload(Blocks.REDSTONE_BLOCK.defaultBlockState()),
                        payload(Blocks.AIR.defaultBlockState())
                )),
                List.of(),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, analyzed.batches().size());
        assertTrue(analyzed.mechanismReplayScope().positions().contains(pos));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(2, 63, 3)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(2, 65, 3)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(3, 64, 3)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(1, 64, 3)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(2, 64, 4)));
        assertTrue(analyzed.mechanismReplayScope().positions().contains(new BlockPoint(2, 64, 2)));
        assertTrue(analyzed.mechanismReplayScope().sections().contains(new ChunkSectionPoint(0, 0, 4)));
    }

    @Test
    void analyzedPrepareCollectsMechanismScopeForPropertyBasedMechanisms() throws Exception {
        BlockPoint pos = new BlockPoint(2, 64, 3);

        PreparedWorldChangeBatches analyzed = this.preparer.prepareAnalyzed(
                null,
                List.of(new StoredBlockChange(
                        pos,
                        payload(Blocks.COPPER_BULB.defaultBlockState()),
                        payload(Blocks.AIR.defaultBlockState())
                )),
                List.of(),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertTrue(analyzed.mechanismReplayScope().positions().contains(pos));
        assertTrue(analyzed.mechanismReplayScope().sections().contains(new ChunkSectionPoint(0, 0, 4)));
    }

    @Test
    void analyzedPrepareLeavesOrdinaryChangesOutOfMechanismScope() throws Exception {
        PreparedWorldChangeBatches analyzed = this.preparer.prepareAnalyzed(
                null,
                List.of(new StoredBlockChange(
                        new BlockPoint(2, 64, 3),
                        payload(Blocks.DIRT.defaultBlockState()),
                        payload(Blocks.STONE.defaultBlockState())
                )),
                List.of(),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, analyzed.batches().size());
        assertTrue(analyzed.mechanismReplayScope().isEmpty());
    }

    @Test
    void preparesExplicitTargetStatesWithReplayHint() throws Exception {
        BlockPoint pos = new BlockPoint(2, 64, 3);

        List<PreparedChunkBatch> batches = this.preparer.prepareTargetStates(
                null,
                java.util.Map.of(pos, StatePayload.air()),
                PreparedBlockPlacement.ReplayHint.FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_MECHANISM
        );

        assertEquals(1, batches.size());
        PreparedBlockPlacement placement = batches.getFirst().placements().getFirst();
        assertEquals(pos.toBlockPos(), placement.pos());
        assertEquals(Blocks.AIR.defaultBlockState(), placement.state());
        assertTrue(placement.replayHint().forcesFinalReplay());
        assertTrue(placement.replayHint().suppressesPostReplayMechanism());
    }

    @Test
    void blockChangesAddSettledPistonHeadCompanionForExtendedBase() throws Exception {
        BlockPos base = new BlockPos(0, 64, 0);
        BlockState retracted = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST);
        BlockState extended = retracted.setValue(PistonBaseBlock.EXTENDED, true);

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                List.of(new StoredBlockChange(
                        BlockPoint.from(base),
                        payload(retracted),
                        payload(extended)
                )),
                List.of(),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        assertEquals(extended, stateAt(batches.getFirst(), base));
        assertEquals(Blocks.PISTON_HEAD, stateAt(batches.getFirst(), base.east()).getBlock());
        assertEquals(Direction.EAST, stateAt(batches.getFirst(), base.east()).getValue(PistonHeadBlock.FACING));
    }

    @Test
    void blockChangesReplaceNormalizedMovingPistonAirWithSettledHead() throws Exception {
        BlockPos base = new BlockPos(0, 64, 0);
        BlockState retracted = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST);
        BlockState extended = retracted.setValue(PistonBaseBlock.EXTENDED, true);

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                List.of(
                        new StoredBlockChange(
                                BlockPoint.from(base),
                                payload(retracted),
                                payload(extended)
                        ),
                        new StoredBlockChange(
                                BlockPoint.from(base.east()),
                                payload(Blocks.MOVING_PISTON.defaultBlockState()),
                                payload(Blocks.AIR.defaultBlockState())
                        )
                ),
                List.of(),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        assertEquals(Blocks.PISTON_HEAD, stateAt(batches.getFirst(), base.east()).getBlock());
        assertEquals(Direction.EAST, stateAt(batches.getFirst(), base.east()).getValue(PistonHeadBlock.FACING));
    }

    @Test
    void blockChangesClearOldPistonHeadWhenExtendedBaseRetracts() throws Exception {
        BlockPos base = new BlockPos(0, 64, 0);
        BlockState extended = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, true);
        BlockState retracted = extended.setValue(PistonBaseBlock.EXTENDED, false);

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                List.of(new StoredBlockChange(
                        BlockPoint.from(base),
                        payload(extended),
                        payload(retracted)
                )),
                List.of(),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        assertEquals(retracted, stateAt(batches.getFirst(), base));
        assertEquals(Blocks.AIR.defaultBlockState(), stateAt(batches.getFirst(), base.east()));
    }

    @Test
    void undoRedoRestoresRetractedPistonBaseFromTransientMovingBaseTarget() throws Exception {
        BlockPos base = new BlockPos(0, 64, 0);
        BlockState retracted = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, false);
        BlockState extended = retracted.setValue(PistonBaseBlock.EXTENDED, true);

        List<PreparedChunkBatch> batches = this.preparer.prepareUndoRedo(
                null,
                List.of(new StoredBlockChange(
                        BlockPoint.from(base),
                        payload(Blocks.MOVING_PISTON.defaultBlockState()),
                        payload(extended)
                )),
                List.of(),
                false,
                null,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        assertEquals(retracted, stateAt(batches.getFirst(), base));
        assertEquals(Blocks.AIR.defaultBlockState(), stateAt(batches.getFirst(), base.east()));
    }

    @Test
    void sectionFramesReplaceNormalizedMovingPistonAirWithSettledHeadCompanion() throws Exception {
        BlockState retracted = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST);
        BlockState extended = retracted.setValue(PistonBaseBlock.EXTENDED, true);
        int[] blockEntityIds = {-1, -1};

        PatchSectionFrame frame = new PatchSectionFrame(
                0,
                0,
                4,
                mask(2),
                List.of(stateTag(retracted), stateTag(Blocks.MOVING_PISTON.defaultBlockState())),
                List.of(stateTag(extended), stateTag(Blocks.AIR.defaultBlockState())),
                new int[] {0, 1},
                new int[] {0, 1},
                List.of(),
                List.of(),
                blockEntityIds,
                blockEntityIds
        );

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                new PatchSectionWorldChanges(List.of(frame), List.of()),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        BlockState head = stateAt(batches.getFirst(), new BlockPos(1, 64, 0));
        assertEquals(Blocks.PISTON_HEAD, head.getBlock());
        assertEquals(Direction.EAST, head.getValue(PistonHeadBlock.FACING));
    }

    @Test
    void sectionFramesRestoreRetractedBaseFromTransientMovingPistonTarget() throws Exception {
        BlockState retracted = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, false);
        BlockState extended = retracted.setValue(PistonBaseBlock.EXTENDED, true);
        int[] blockEntityIds = {-1};

        PatchSectionFrame frame = new PatchSectionFrame(
                0,
                0,
                4,
                mask(1),
                List.of(stateTag(Blocks.MOVING_PISTON.defaultBlockState())),
                List.of(stateTag(extended)),
                new int[] {0},
                new int[] {0},
                List.of(),
                List.of(),
                blockEntityIds,
                blockEntityIds
        );

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                new PatchSectionWorldChanges(List.of(frame), List.of()),
                false,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, batches.size());
        assertEquals(retracted, stateAt(batches.getFirst(), new BlockPos(0, 64, 0)));
    }

    @Test
    void sectionFramesDecodeRepeatedPaletteTagsOnceAndKeepBlockEntities() throws Exception {
        CountingBlockStateDecoder decoder = new CountingBlockStateDecoder();
        WorldChangeBatchPreparer preparer = new WorldChangeBatchPreparer(decoder);
        int changedCells = SectionApplySafetyClassifier.NATIVE_DENSE_THRESHOLD;
        int[] stateIds = new int[changedCells];
        int[] oldBlockEntityIds = new int[changedCells];
        int[] blockEntityIds = new int[changedCells];
        Arrays.fill(oldBlockEntityIds, -1);
        Arrays.fill(blockEntityIds, -1);
        for (int index = 0; index < changedCells; index++) {
            stateIds[index] = index & 1;
        }
        blockEntityIds[0] = 0;

        PatchSectionFrame frame = new PatchSectionFrame(
                0,
                0,
                4,
                mask(changedCells),
                List.of(stateTag("minecraft:air")),
                List.of(stateTag("minecraft:stone"), stateTag("minecraft:stone")),
                new int[changedCells],
                stateIds,
                List.of(),
                List.of(blockEntityTag("minecraft:chest")),
                oldBlockEntityIds,
                blockEntityIds
        );

        List<PreparedChunkBatch> batches = preparer.prepare(
                null,
                new PatchSectionWorldChanges(List.of(frame), List.of()),
                true,
                NO_OP,
                EntityApplyMode.DELTA
        );

        assertEquals(1, decoder.callsFor("minecraft:stone"));
        assertEquals(0, batches.getFirst().placements().size());
        assertEquals(1, batches.getFirst().nativeSections().size());
        PreparedSectionApplyBatch sectionBatch = batches.getFirst().nativeSections().getFirst();
        assertEquals(SectionApplyPath.SECTION_NATIVE, sectionBatch.safetyProfile().path());
        assertEquals(
                "minecraft:chest",
                sectionBatch.buffer().blockEntityPlan().tagAt(0).getString("id").orElse("")
        );
    }

    private static EntityPayload entity(String entityId, double x) {
        return entity("minecraft:block_display", entityId, x);
    }

    private static EntityPayload entity(String type, String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", entityId);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }

    private static StatePayload payload(net.minecraft.world.level.block.state.BlockState state) {
        return new StatePayload(BlockStateNbtCodec.serializeBlockStateTag(state), null);
    }

    private static net.minecraft.world.level.block.state.BlockState stateAt(PreparedChunkBatch batch, BlockPos pos) {
        PreparedBlockPlacement placement = placementAt(batch, pos);
        return placement == null ? null : placement.state();
    }

    private static PreparedBlockPlacement placementAt(PreparedChunkBatch batch, BlockPos pos) {
        PreparedBlockPlacement resolved = null;
        for (PreparedSectionApplyBatch section : batch.nativeSections()) {
            if (section.sectionY() != Math.floorDiv(pos.getY(), 16)) {
                continue;
            }
            int localIndex = SectionChangeMask.localIndex(pos.getX(), pos.getY(), pos.getZ());
            net.minecraft.world.level.block.state.BlockState state = section.buffer().targetStateAt(localIndex);
            if (state != null) {
                resolved = new PreparedBlockPlacement(
                        pos,
                        state,
                        section.buffer().blockEntityPlan().tagAt(localIndex),
                        section.buffer().replayHintAt(localIndex)
                );
            }
        }
        for (PreparedBlockPlacement placement : batch.placements()) {
            if (placement.pos().equals(pos)) {
                resolved = placement;
            }
        }
        return resolved;
    }

    private static long[] mask(int changedCells) {
        SectionChangeMask.Builder builder = SectionChangeMask.builder();
        for (int index = 0; index < changedCells; index++) {
            builder.set(index);
        }
        return builder.build().words();
    }

    private static CompoundTag stateTag(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    private static CompoundTag stateTag(BlockState state) {
        return BlockStateNbtCodec.serializeBlockStateTag(state);
    }

    private static CompoundTag blockEntityTag(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        return tag;
    }
}
