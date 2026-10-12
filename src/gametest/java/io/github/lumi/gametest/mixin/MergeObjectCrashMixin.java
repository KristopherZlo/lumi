package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import io.github.lumi.storage.repository.WorldObjectRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WorldObjectRepository.class, remap = false)
public abstract class MergeObjectCrashMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private void written(CallbackInfoReturnable<?> callback) { LumiPersistenceCrashProbe.hit("object-written"); }
}
