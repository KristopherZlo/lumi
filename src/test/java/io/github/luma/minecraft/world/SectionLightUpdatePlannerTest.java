package io.github.luma.minecraft.world;

import net.minecraft.SharedConstants;
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
}
