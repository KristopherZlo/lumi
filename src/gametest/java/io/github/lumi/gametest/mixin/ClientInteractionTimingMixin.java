package io.github.lumi.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.lumi.gametest.LumiInteractionTiming;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/** Distinguishes swallowed client input from a delayed server packet. */
@Mixin(value = Minecraft.class, priority = 900)
public abstract class ClientInteractionTimingMixin {
    @WrapMethod(method = "startUseItem")
    private void lumi$observeUse(Operation<Void> original) {
        LumiInteractionTiming.recordClientAttempt();
        original.call();
    }

    @WrapMethod(method = "startAttack")
    private boolean lumi$observeAttack(Operation<Boolean> original) {
        LumiInteractionTiming.recordClientAttempt();
        return original.call();
    }
}
