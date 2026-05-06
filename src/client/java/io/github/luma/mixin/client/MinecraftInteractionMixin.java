package io.github.luma.mixin.client;

import io.github.luma.client.input.LumiShortcutInteractionGate;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public final class MinecraftInteractionMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void luma$suppressUseDuringShortcut(CallbackInfo callback) {
        if (LumiShortcutInteractionGate.getInstance().shouldSuppressWorldInteraction(Minecraft.getInstance())) {
            callback.cancel();
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void luma$suppressAttackDuringShortcut(CallbackInfoReturnable<Boolean> callback) {
        if (LumiShortcutInteractionGate.getInstance().shouldSuppressWorldInteraction(Minecraft.getInstance())) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void luma$suppressContinuedAttackDuringShortcut(boolean attacking, CallbackInfo callback) {
        if (attacking && LumiShortcutInteractionGate.getInstance().shouldSuppressWorldInteraction(Minecraft.getInstance())) {
            callback.cancel();
        }
    }
}
