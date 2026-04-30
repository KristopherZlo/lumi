package io.github.luma.minecraft.world;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PalettedContainerDataSwapperTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void swapsContainerDataWhenPrivateAccessIsAvailable() {
        PalettedContainerDataSwapper swapper = new PalettedContainerDataSwapper();
        Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        PalettedContainer<BlockState> target = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
        PalettedContainer<BlockState> replacement = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
        replacement.getAndSetUnchecked(1, 2, 3, Blocks.STONE.defaultBlockState());

        boolean swapped = swapper.swapData(target, replacement);

        Assertions.assertEquals(swapper.available(), swapped);
        if (swapped) {
            Assertions.assertEquals(Blocks.STONE.defaultBlockState(), target.get(1, 2, 3));
        }
    }
}
