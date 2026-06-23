package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.reflect.Method;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LecternBlockEntityMixinTest {

    @Test
    void wrapsSetPageForLecternPayloadCapture() throws NoSuchMethodException {
        Method method = LecternBlockEntityMixin.class.getDeclaredMethod(
                "luma$wrapSetPage",
                int.class,
                Operation.class
        );

        WrapMethod wrapMethod = method.getAnnotation(WrapMethod.class);

        assertNotNull(wrapMethod, "LecternBlockEntityMixin must wrap setPage for page NBT capture");
        assertArrayEquals(new String[]{"setPage"}, wrapMethod.method());
    }

    @Test
    void wrapsSetBookForLecternBookPayloadCapture() throws NoSuchMethodException {
        Method method = LecternBlockEntityMixin.class.getDeclaredMethod(
                "luma$wrapSetBook",
                ItemStack.class,
                Player.class,
                Operation.class
        );

        WrapMethod wrapMethod = method.getAnnotation(WrapMethod.class);

        assertNotNull(wrapMethod, "LecternBlockEntityMixin must wrap setBook for book NBT capture");
        assertArrayEquals(new String[]{"setBook(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V"}, wrapMethod.method());
    }
}
