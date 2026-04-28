package io.github.luma.gametest;

import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class LumiGameTests implements CustomTestMethodInvoker {

    @GameTest
    public void serverHarnessBoots(GameTestHelper context) {
        context.assertBlockPresent(Blocks.AIR, 0, 0, 0);
        context.succeed();
    }

    @GameTest
    public void fallingBlockSpawnDoesNotCrashEntityCapture(GameTestHelper context) {
        context.setBlock(0, 0, 0, Blocks.STONE);
        context.setBlock(0, 1, 0, Blocks.AIR);
        context.setBlock(0, 2, 0, Blocks.SAND);
        context.tickBlock(new BlockPos(0, 2, 0));
        context.succeedWhenBlockPresent(Blocks.SAND, 0, 1, 0);
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
        context.setBlock(0, 0, 0, Blocks.AIR);
        method.invoke(this, context);
    }
}
