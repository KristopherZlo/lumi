package io.github.luma.minecraft.world;

import io.github.luma.domain.model.BlockPoint;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                true
        );

        assertEquals(1, batches.size());
        assertEquals(1, batches.getFirst().entityBatch().entitiesToSpawn().size());
        assertEquals(2, batches.getFirst().chunk().x());
    }

    @Test
    void preparesOldValueEntityBatchesByInvertingChanges() throws Exception {
        String entityId = "00000000-0000-0000-0000-000000000002";

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:block_display", null, entity(entityId, 1.0D))),
                false
        );

        assertEquals(1, batches.size());
        assertEquals(List.of(entityId), batches.getFirst().entityBatch().entityIdsToRemove());
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
    void undoRedoLargeSimpleSectionsPrepareAsRewriteBatches() throws Exception {
        List<StoredBlockChange> changes = java.util.stream.IntStream
                .range(0, SectionApplySafetyClassifier.CONTAINER_REWRITE_THRESHOLD)
                .mapToObj(index -> new StoredBlockChange(
                        new BlockPoint(index & 15, 64 + ((index >>> 8) & 15), (index >>> 4) & 15),
                        payload(Blocks.AIR.defaultBlockState()),
                        payload(Blocks.STONE.defaultBlockState())
                ))
                .toList();

        List<PreparedChunkBatch> batches = this.preparer.prepareUndoRedo(null, changes, List.of(), true, null);

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
                null
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
                null
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
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
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

    private static CompoundTag blockEntityTag(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        return tag;
    }
}
