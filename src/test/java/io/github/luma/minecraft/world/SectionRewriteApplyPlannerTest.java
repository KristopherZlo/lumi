package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SectionRewriteApplyPlannerTest {

    private final SectionRewriteApplyPlanner planner = new SectionRewriteApplyPlanner(
            new PersistentBlockStatePolicy(),
            new BlockPlacementUpdateDecider()
    );

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void fullSectionPlanWritesFreshReplacementFromEveryTargetCell() {
        LevelChunkSection section = sectionWithDefault(Blocks.AIR.defaultBlockState());
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
        PreparedSectionApplyBatch batch = fullSectionBatch(Blocks.STONE.defaultBlockState());

        SectionRewriteApplyPlan plan = this.planner.plan(null, section, batch);

        Assertions.assertTrue(plan.rebuildsEntireSection());
        Assertions.assertEquals(SectionChangeMask.ENTRY_COUNT, plan.writeCount());
        Assertions.assertEquals(SectionChangeMask.ENTRY_COUNT - 1, plan.changedBlockCount());

        PalettedContainer<BlockState> replacement = section.getStates().recreate();
        plan.writeTo(replacement);

        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), replacement.get(0, 0, 0));
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), replacement.get(15, 15, 15));
    }

    @Test
    void partialPlanOnlyWritesCellsThatActuallyChange() {
        LevelChunkSection section = sectionWithDefault(Blocks.AIR.defaultBlockState());
        LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(0);
        builder.set(0, Blocks.AIR.defaultBlockState(), null);
        builder.set(1, Blocks.STONE.defaultBlockState(), null);
        PreparedSectionApplyBatch batch = sectionBatch(builder.build(), false);

        SectionRewriteApplyPlan plan = this.planner.plan(null, section, batch);

        Assertions.assertFalse(plan.rebuildsEntireSection());
        Assertions.assertEquals(1, plan.writeCount());
        Assertions.assertEquals(1, plan.changedBlockCount());
        Assertions.assertEquals(1, plan.skippedBlockCount(batch.changedCellCount()));
    }

    @Test
    void incompleteFullSectionFlagFallsBackToPartialPlan() {
        LevelChunkSection section = sectionWithDefault(Blocks.AIR.defaultBlockState());
        LumiSectionBuffer buffer = LumiSectionBuffer.builder(0)
                .set(0, Blocks.STONE.defaultBlockState(), null)
                .build();

        SectionRewriteApplyPlan plan = this.planner.plan(null, section, sectionBatch(buffer, true));

        Assertions.assertFalse(plan.rebuildsEntireSection());
        Assertions.assertEquals(1, plan.writeCount());
        Assertions.assertEquals(1, plan.changedBlockCount());
    }

    private static PreparedSectionApplyBatch fullSectionBatch(BlockState state) {
        LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(0);
        for (int index = 0; index < SectionChangeMask.ENTRY_COUNT; index++) {
            builder.set(index, state, null);
        }
        return sectionBatch(builder.build(), true);
    }

    private static PreparedSectionApplyBatch sectionBatch(LumiSectionBuffer buffer, boolean fullSection) {
        return new PreparedSectionApplyBatch(
                new ChunkPoint(0, 0),
                0,
                buffer,
                SectionApplySafetyProfile.sectionRewrite("test"),
                fullSection
        );
    }

    private static LevelChunkSection sectionWithDefault(BlockState state) {
        Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        return new LevelChunkSection(new PalettedContainer<>(state, strategy), null);
    }
}
