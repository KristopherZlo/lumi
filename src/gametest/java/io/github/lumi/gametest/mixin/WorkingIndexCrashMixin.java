package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MutationDurabilityTracker.class, remap = false)
public abstract class WorkingIndexCrashMixin {
    @Inject(method = "complete", at = @At("RETURN"))
    private void cleared(CallbackInfo callback) { LumiPersistenceCrashProbe.hit("working-cleared"); }
}
