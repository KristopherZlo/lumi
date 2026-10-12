package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import io.github.lumi.storage.repository.VersionPreviewRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VersionPreviewRepository.class, remap = false)
public abstract class PreviewCrashMixin {
    @Inject(method = "save", at = @At("RETURN"))
    private void written(CallbackInfo callback) { LumiPersistenceCrashProbe.hit("preview-written"); }
}
