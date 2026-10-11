package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiInteractionTiming;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Distinguishes swallowed client input from a delayed server packet. */
@Mixin(value = Minecraft.class, priority = 2000)
public abstract class ClientInteractionTimingMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"))
    private void lumi$observeUse(CallbackInfo callback) {
        LumiInteractionTiming.recordClientAttempt();
    }

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void lumi$observeAttack(CallbackInfoReturnable<Boolean> callback) {
        LumiInteractionTiming.recordClientAttempt();
    }
}
