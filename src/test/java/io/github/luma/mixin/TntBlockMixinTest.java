package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
}
