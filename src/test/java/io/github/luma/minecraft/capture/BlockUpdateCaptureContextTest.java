package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockUpdateCaptureContextTest {

    private final BlockUpdateCaptureContext context = BlockUpdateCaptureContext.getInstance();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void scopesRedstoneAndMechanismStates() {
        assertTrue(this.context.shouldScope(Blocks.LEVER.defaultBlockState()));
        assertTrue(this.context.shouldScope(Blocks.REDSTONE_WIRE.defaultBlockState()));
        assertTrue(this.context.shouldScope(Blocks.REPEATER.defaultBlockState()));
        assertTrue(this.context.shouldScope(Blocks.OBSERVER.defaultBlockState()));
        assertTrue(this.context.shouldScope(Blocks.STONE_BUTTON.defaultBlockState()));
        assertTrue(this.context.shouldScope(Blocks.STONE_PRESSURE_PLATE.defaultBlockState()));
        assertTrue(this.context.shouldScope(Blocks.PISTON.defaultBlockState()));
        assertTrue(this.context.shouldScope(Blocks.PISTON_HEAD.defaultBlockState()));
    }

    @Test
    void skipsOrdinaryBlockUpdates() {
        assertFalse(this.context.shouldScope(Blocks.STONE.defaultBlockState()));
        assertFalse(this.context.shouldScope(Blocks.OAK_PLANKS.defaultBlockState()));
    }

    @Test
    void keepsFluidFalloutInFluidSourceContext() {
        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushSource(WorldMutationSource.FLUID, "water", "action-1", true)) {
            assertNull(this.context.pushFor(Blocks.LEVER.defaultBlockState()));
            assertTrue(WorldMutationContext.currentSource() == WorldMutationSource.FLUID);
        }
    }
}
