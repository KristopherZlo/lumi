package io.github.lumi.gametest.mixin;

import io.github.lumi.domain.service.CapturedWorldState;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.gametest.LumiOperationTestPause;
import io.github.lumi.minecraft.operation.SaveCaptureOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SaveCaptureOperation.class, remap = false)
public abstract class SavePublicationPauseMixin {
    @Inject(method = "publish", at = @At("HEAD"))
    private void awaitTest(CapturedWorldState captured, CallbackInfoReturnable<SaveResult> callback) {
        LumiOperationTestPause.beforeSavePublication();
    }
}
