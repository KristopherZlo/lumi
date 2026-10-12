package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import io.github.lumi.minecraft.world.RestoreCrashInjection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RestoreCrashInjection.class, remap = false)
public abstract class MergeApplyCrashMixin {
    @Inject(method = "hit", at = @At("HEAD"))
    private static void hit(RestoreCrashInjection.Cutpoint cut, CallbackInfo callback) {
        LumiPersistenceCrashProbe.hit(cut.name().toLowerCase(java.util.Locale.ROOT));
    }
}
