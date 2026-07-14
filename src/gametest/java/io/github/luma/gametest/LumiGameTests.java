package io.github.luma.gametest;

import io.github.luma.minecraft.world.WorldOperationManager;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

public final class LumiGameTests implements CustomTestMethodInvoker {

    @GameTest(maxTicks = 400)
    public void fallingBlockSpawnDoesNotCrashEntityCapture(GameTestHelper context) {
        context.runAfterDelay(1L, () -> this.startFallingBlockTestWhenIdle(context));
    }

    private void startFallingBlockTestWhenIdle(GameTestHelper context) {
        if (WorldOperationManager.getInstance().hasActiveOperation(context.getLevel().getServer())) {
            context.runAfterDelay(1L, () -> this.startFallingBlockTestWhenIdle(context));
            return;
        }
        context.setBlock(0, 0, 0, Blocks.STONE);
        context.setBlock(0, 1, 0, Blocks.AIR);
        context.setBlock(0, 2, 0, Blocks.SAND);
        context.tickBlock(new BlockPos(0, 2, 0));
        context.succeedWhenBlockPresent(Blocks.SAND, 0, 1, 0);
    }

    @GameTest
    public void cropRandomTickDoesNotCrashGrowthCapture(GameTestHelper context) {
        BlockPos farmland = new BlockPos(0, 0, 0);
        BlockPos crop = farmland.above();
        context.setBlock(farmland, Blocks.FARMLAND);
        context.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0));
        context.randomTick(crop);
        context.succeed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
        context.setBlock(0, 0, 0, Blocks.AIR);
        method.invoke(this, context);
    }
}
