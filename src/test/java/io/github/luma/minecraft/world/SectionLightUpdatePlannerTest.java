package io.github.luma.minecraft.world;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SectionLightUpdatePlannerTest {

    private final SectionLightUpdatePlanner planner = new SectionLightUpdatePlanner();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void skipsEquivalentLightTransitions() {
        Assertions.assertFalse(this.planner.requiresLightCheck(
                Blocks.STONE.defaultBlockState(),
                Blocks.STONE.defaultBlockState()
        ));
    }

    @Test
    void skipsFullOpaqueSwapsWithEquivalentLightProperties() {
        Assertions.assertFalse(this.planner.requiresLightCheck(
                Blocks.STONE.defaultBlockState(),
                Blocks.DEEPSLATE.defaultBlockState()
        ));
    }

    @Test
    void checksOpaqueAndAirTransitions() {
        Assertions.assertTrue(this.planner.requiresLightCheck(
                Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        ));
    }

    @Test
    void checksTransparentAndSolidTransitions() {
        Assertions.assertTrue(this.planner.requiresLightCheck(
                Blocks.GLASS.defaultBlockState(),
                Blocks.STONE.defaultBlockState()
        ));
    }

    @Test
    void checksEmissiveTransitions() {
        Assertions.assertTrue(this.planner.requiresLightCheck(
                Blocks.GLOWSTONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        ));
    }

    @Test
    void checksFluidTransitions() {
        Assertions.assertTrue(this.planner.requiresLightCheck(
                Blocks.WATER.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        ));
    }

    @Test
    void checksSkylightTransitions() {
        Assertions.assertTrue(this.planner.requiresLightCheck(
                Blocks.GLASS.defaultBlockState(),
                Blocks.TINTED_GLASS.defaultBlockState()
        ));
    }

    @Test
    void plansOnlyLightRelevantPositionsForDeferredBatchApply() {
        SectionLightUpdateBatch batch = new SectionLightUpdateBatch();

        Assertions.assertFalse(this.planner.plan(
                batch,
                new BlockPos(1, 64, 1),
                Blocks.STONE.defaultBlockState(),
                Blocks.DEEPSLATE.defaultBlockState()
        ));
        Assertions.assertTrue(this.planner.plan(
                batch,
                new BlockPos(2, 64, 2),
                Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        ));

        Assertions.assertEquals(1, batch.size());
        Assertions.assertEquals(new BlockPos(2, 64, 2), batch.positions().getFirst());
        Assertions.assertEquals(0, batch.exactPositions().size());
        Assertions.assertEquals(1, batch.surfaceCandidatePositions().size());
    }

    @Test
    void keepsEmissiveTransitionsExactForDeferredBatchApply() {
        SectionLightUpdateBatch batch = new SectionLightUpdateBatch();

        Assertions.assertTrue(this.planner.plan(
                batch,
                new BlockPos(2, 64, 2),
                Blocks.GLOWSTONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        ));

        Assertions.assertEquals(1, batch.exactPositions().size());
        Assertions.assertEquals(0, batch.surfaceCandidatePositions().size());
    }

    @Test
    void queuesLightChecksWhenOperationContextIsActive() {
        SectionLightUpdateBatch batch = new SectionLightUpdateBatch();
        this.planner.plan(
                batch,
                new BlockPos(2, 64, 2),
                Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        );
        WorldLightUpdateQueue queue = new WorldLightUpdateQueue();

        WorldLightUpdateContext.push(queue);
        try {
            Assertions.assertEquals(0, this.planner.apply(null, batch));
        } finally {
            WorldLightUpdateContext.pop();
        }

        Assertions.assertEquals(1, queue.pendingCount());
    }
}
