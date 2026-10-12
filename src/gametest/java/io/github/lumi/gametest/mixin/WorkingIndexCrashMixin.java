package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorkingIndexRepository.class, remap = false)
public abstract class WorkingIndexCrashMixin {
    @Inject(method = "clearCaptured", at = @At("RETURN"))
    private void cleared(CallbackInfo callback) { LumiPersistenceCrashProbe.hit("working-cleared"); }
}
