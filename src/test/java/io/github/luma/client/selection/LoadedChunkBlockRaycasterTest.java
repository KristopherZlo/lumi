package io.github.luma.client.selection;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadedChunkBlockRaycasterTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void portalBlocksAreSelectableTargets() {
        assertTrue(LoadedChunkBlockRaycaster.isSelectableTarget(Blocks.NETHER_PORTAL.defaultBlockState()));
        assertTrue(LoadedChunkBlockRaycaster.isSelectableTarget(Blocks.END_PORTAL.defaultBlockState()));
        assertFalse(LoadedChunkBlockRaycaster.isSelectableTarget(Blocks.AIR.defaultBlockState()));
    }
}
