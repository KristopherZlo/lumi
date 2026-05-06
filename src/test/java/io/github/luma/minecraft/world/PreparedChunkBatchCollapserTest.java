package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedChunkBatchCollapserTest {

    private final PreparedChunkBatchCollapser collapser = new PreparedChunkBatchCollapser();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void collapseKeepsOnlyLatestSparsePlacementPerBlock() {
        PreparedChunkBatch first = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(
                        new PreparedBlockPlacement(new BlockPos(1, 64, 1), Blocks.STONE.defaultBlockState(), null),
                        new PreparedBlockPlacement(new BlockPos(2, 64, 2), Blocks.DIRT.defaultBlockState(), null)
                )
        );
        PreparedChunkBatch second = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(new PreparedBlockPlacement(new BlockPos(1, 64, 1), Blocks.GOLD_BLOCK.defaultBlockState(), null))
        );

        List<PreparedChunkBatch> collapsed = this.collapser.collapse(List.of(first, second));

        assertEquals(1, collapsed.size());
        assertEquals(2, collapsed.getFirst().placements().size());
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), stateAt(collapsed.getFirst(), new BlockPos(1, 64, 1)));
    }

    @Test
    void fullSectionSourceStaysNativeInsteadOfFlatteningToPlacements() {
        PreparedChunkBatch batch = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(),
                List.of(fullSection(0, 4, Blocks.STONE.defaultBlockState())),
                EntityBatch.empty()
        );

        List<PreparedChunkBatch> collapsed = this.collapser.collapse(List.of(batch));

        assertEquals(1, collapsed.size());
        assertTrue(collapsed.getFirst().placements().isEmpty());
        assertEquals(1, collapsed.getFirst().nativeSections().size());
        assertEquals(SectionApplyPath.SECTION_REWRITE, collapsed.getFirst().nativeSections().getFirst().safetyProfile().path());
    }

    @Test
    void sparsePlacementOverridesFullSectionCell() {
        BlockPos overridePos = new BlockPos(3, 65, 4);
        PreparedChunkBatch full = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(),
                List.of(fullSection(0, 4, Blocks.STONE.defaultBlockState())),
                EntityBatch.empty()
        );
        PreparedChunkBatch override = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(new PreparedBlockPlacement(overridePos, Blocks.GLASS.defaultBlockState(), null))
        );

        List<PreparedChunkBatch> collapsed = this.collapser.collapse(List.of(full, override));

        assertEquals(Blocks.GLASS.defaultBlockState(), stateAt(collapsed.getFirst(), overridePos));
        assertTrue(collapsed.getFirst().placements().isEmpty());
        assertFalse(collapsed.getFirst().nativeSections().isEmpty());
    }

    @Test
    void entityOnlyBatchesArePreserved() {
        CompoundTag entity = entity("00000000-0000-0000-0000-000000000050", 32.0D);
        PreparedChunkBatch batch = new PreparedChunkBatch(
                new ChunkPoint(2, 3),
                List.of(),
                new EntityBatch(List.of(entity), List.of(), List.of())
        );

        List<PreparedChunkBatch> collapsed = this.collapser.collapse(List.of(batch));

        assertEquals(1, collapsed.size());
        assertEquals(1, collapsed.getFirst().entityBatch().entitiesToSpawn().size());
    }

    @Test
    void laterEntityRemovalWinsOverEarlierUpdate() {
        String entityId = "00000000-0000-0000-0000-000000000051";
        PreparedChunkBatch update = new PreparedChunkBatch(
                new ChunkPoint(1, 0),
                List.of(),
                new EntityBatch(List.of(), List.of(), List.of(entity(entityId, 16.0D)))
        );
        PreparedChunkBatch removal = new PreparedChunkBatch(
                new ChunkPoint(1, 0),
                List.of(),
                new EntityBatch(List.of(), List.of(entityId), List.of())
        );

        List<PreparedChunkBatch> collapsed = this.collapser.collapse(List.of(update, removal));

        assertEquals(1, collapsed.size());
        assertEquals(List.of(entityId), collapsed.getFirst().entityBatch().entityIdsToRemove());
        assertTrue(collapsed.getFirst().entityBatch().entitiesToUpdate().isEmpty());
    }

    @Test
    void spawnThenRemovalCollapsesToNoEntityOperation() {
        String entityId = "00000000-0000-0000-0000-000000000052";
        PreparedChunkBatch spawn = new PreparedChunkBatch(
                new ChunkPoint(1, 0),
                List.of(),
                new EntityBatch(List.of(entity(entityId, 16.0D)), List.of(), List.of())
        );
        PreparedChunkBatch removal = new PreparedChunkBatch(
                new ChunkPoint(1, 0),
                List.of(),
                new EntityBatch(List.of(), List.of(entityId), List.of())
        );

        List<PreparedChunkBatch> collapsed = this.collapser.collapse(List.of(spawn, removal));

        assertTrue(collapsed.isEmpty());
    }

    @Test
    void removalThenSpawnBecomesReplacingUpdate() {
        String entityId = "00000000-0000-0000-0000-000000000053";
        PreparedChunkBatch removal = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(),
                new EntityBatch(List.of(), List.of(entityId), List.of())
        );
        PreparedChunkBatch spawn = new PreparedChunkBatch(
                new ChunkPoint(2, 0),
                List.of(),
                new EntityBatch(List.of(entity(entityId, 32.0D)), List.of(), List.of())
        );

        List<PreparedChunkBatch> collapsed = this.collapser.collapse(List.of(removal, spawn));

        assertEquals(1, collapsed.size());
        assertEquals(2, collapsed.getFirst().chunk().x());
        assertTrue(collapsed.getFirst().entityBatch().entitiesToSpawn().isEmpty());
        assertEquals(1, collapsed.getFirst().entityBatch().entitiesToUpdate().size());
    }

    @Test
    void latestEntityTargetMovesToFinalChunk() {
        String entityId = "00000000-0000-0000-0000-000000000054";
        PreparedChunkBatch first = new PreparedChunkBatch(
                new ChunkPoint(1, 0),
                List.of(),
                new EntityBatch(List.of(), List.of(), List.of(entity(entityId, 16.0D)))
        );
        PreparedChunkBatch second = new PreparedChunkBatch(
                new ChunkPoint(3, 0),
                List.of(),
                new EntityBatch(List.of(), List.of(), List.of(entity(entityId, 48.0D)))
        );

        List<PreparedChunkBatch> collapsed = this.collapser.collapse(List.of(first, second));

        assertEquals(1, collapsed.size());
        assertEquals(3, collapsed.getFirst().chunk().x());
        assertEquals(48.0D, collapsed.getFirst().entityBatch()
                .entitiesToUpdate()
                .getFirst()
                .getListOrEmpty("Pos")
                .getDoubleOr(0, 0.0D));
    }

    @Test
    void nonFullSparseConnectedBlocksAreCompleted() {
        PreparedBlockPlacement lowerDoor = new PreparedBlockPlacement(
                new BlockPos(1, 64, 1),
                Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER),
                null
        );

        List<PreparedChunkBatch> collapsed = this.collapser.collapse(List.of(
                new PreparedChunkBatch(new ChunkPoint(0, 0), List.of(lowerDoor))
        ));

        assertEquals(2, collapsed.getFirst().placements().size());
        assertEquals(
                Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER),
                stateAt(collapsed.getFirst(), new BlockPos(1, 65, 1))
        );
    }

    private static PreparedSectionApplyBatch fullSection(
            int chunkX,
            int sectionY,
            net.minecraft.world.level.block.state.BlockState state
    ) {
        LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(sectionY);
        for (int localIndex = 0; localIndex < SectionChangeMask.ENTRY_COUNT; localIndex++) {
            builder.set(localIndex, state, null);
        }
        LumiSectionBuffer buffer = builder.build();
        return new PreparedSectionApplyBatch(
                new ChunkPoint(chunkX, 0),
                sectionY,
                buffer,
                SectionApplySafetyProfile.sectionRewrite("test-full-section"),
                true
        );
    }

    private static net.minecraft.world.level.block.state.BlockState stateAt(PreparedChunkBatch batch, BlockPos pos) {
        List<PreparedBlockPlacement> placements = new ArrayList<>(batch.placements());
        for (PreparedSectionApplyBatch section : batch.nativeSections()) {
            if (section.sectionY() != Math.floorDiv(pos.getY(), 16)) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState state = section.buffer().targetStateAt(
                    SectionChangeMask.localIndex(pos.getX(), pos.getY(), pos.getZ())
            );
            if (state != null) {
                return state;
            }
        }
        for (PreparedBlockPlacement placement : placements) {
            if (placement.pos().equals(pos)) {
                return placement.state();
            }
        }
        return null;
    }

    private static CompoundTag entity(String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.putString("UUID", entityId);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return tag;
    }
}
