package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.world.ExactReplayStateGuard;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TntBlockMixinTest {

    @Test
    void wrapsWasExplodedForChainedTntCausality() throws NoSuchMethodException {
        Method method = TntBlockMixin.class.getDeclaredMethod(
                "luma$wrapWasExploded",
                ServerLevel.class,
                BlockPos.class,
                Explosion.class,
                Operation.class
        );

        WrapMethod wrapMethod = method.getAnnotation(WrapMethod.class);
        assertNotNull(wrapMethod, "TntBlockMixin must wrap TntBlock.wasExploded for TNT-chain action ownership");
        assertArrayEquals(new String[]{"wasExploded"}, wrapMethod.method());
        assertEquals(Void.TYPE, method.getReturnType());
        assertTrue(Modifier.isPrivate(method.getModifiers()));
    }

    @Test
    void wrapsSharedPrimeForAllPrimingCallers() throws NoSuchMethodException {
        Method method = TntBlockMixin.class.getDeclaredMethod(
                "luma$wrapPrime",
                Level.class,
                BlockPos.class,
                LivingEntity.class,
                Operation.class
        );

        WrapMethod wrapMethod = method.getAnnotation(WrapMethod.class);
        assertNotNull(wrapMethod, "TntBlockMixin must wrap TntBlock.prime so fire and dispenser priming keep action ownership");
        assertArrayEquals(new String[]{"prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)Z"}, wrapMethod.method());
        assertEquals(Boolean.TYPE, method.getReturnType());
        assertTrue(Modifier.isPrivate(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
    }

    @Test
    void consultsExactReplayGuardBeforeTntActivation() throws NoSuchFieldException {
        Field guard = TntBlockMixin.class.getDeclaredField("LUMA_EXACT_REPLAY_STATE_GUARD");

        assertEquals(ExactReplayStateGuard.class, guard.getType());
        assertTrue(Modifier.isPrivate(guard.getModifiers()));
        assertTrue(Modifier.isStatic(guard.getModifiers()));
    }

    @Test
    void logsReplayActivationDecisionsForTntCallbacks() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/TntBlockMixin.java"));

        assertTrue(source.contains("LumaLoadLog.event(\"tnt-replay\", \"activation\""));
        assertTrue(source.contains("callback=\" + callback"));
        assertTrue(source.contains("frozen=\" + frozen"));
        assertTrue(source.contains("boolean suppressed = frozen || LUMA_REPLAY_ACTIVATION_POLICY.shouldSuppressActivation("));
        assertTrue(source.contains("luma$shouldSuppressReplayActivation(level, pos, \"onPlace\")"));
        assertTrue(source.contains("luma$shouldSuppressReplayActivation(level, pos, \"neighborChanged\")"));
        assertTrue(source.contains("luma$shouldSuppressReplayActivation(level, pos, \"wasExploded\")"));
        assertTrue(source.contains("luma$shouldSuppressReplayActivation(level, pos, \"prime\")"));
    }
}
