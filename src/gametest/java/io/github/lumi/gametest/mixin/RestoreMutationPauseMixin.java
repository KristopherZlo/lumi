package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiOperationTestPause;
import io.github.lumi.minecraft.operation.RestoreOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RestoreOperation.class, remap = false)
public abstract class RestoreMutationPauseMixin {
    @Inject(method = "advance", at = @At("HEAD"), cancellable = true)
    private void deferTest(long deadlineNanos, CallbackInfo callback) {
        if (LumiOperationTestPause.deferRestoreAdvance()) callback.cancel();
    }
}
