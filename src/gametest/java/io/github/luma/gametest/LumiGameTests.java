package io.github.luma.gametest;

import io.github.luma.minecraft.animal.AnimalMoveManager;
import io.github.luma.minecraft.animal.AnimalMovePlan;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class LumiGameTests implements CustomTestMethodInvoker {

    @GameTest
    public void serverHarnessBoots(GameTestHelper context) {
        context.assertBlockPresent(Blocks.AIR, 0, 0, 0);
        context.succeed();
    }

    @GameTest
    public void animalMoveManagerTracksAndStopsCow(GameTestHelper context) {
        AnimalMoveManager manager = AnimalMoveManager.getInstance();
        manager.stopAll(context.getLevel());

        Animal cow = context.spawn(EntityType.COW, 1, 2, 1);
        AnimalMovePlan plan = new AnimalMovePlan(
                context.absoluteVec(new Vec3(4.0D, 2.0D, 1.0D)),
                Optional.empty(),
                false
        );

        int started = manager.start(context.getLevel(), java.util.List.of(cow), plan);
        context.assertValueEqual(started, 1, "animove should track the selected cow");
        context.assertValueEqual(manager.activeSessionCount(context.getLevel()), 1, "animove should keep one active session");

        int stopped = manager.stop(context.getLevel(), Set.of(cow.getUUID()));
        context.assertValueEqual(stopped, 1, "animovestop should stop the selected cow");
        context.assertValueEqual(manager.activeSessionCount(context.getLevel()), 0, "animovestop should clear the session");
        context.succeed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
        context.setBlock(0, 0, 0, Blocks.AIR);
        method.invoke(this, context);
    }
}
