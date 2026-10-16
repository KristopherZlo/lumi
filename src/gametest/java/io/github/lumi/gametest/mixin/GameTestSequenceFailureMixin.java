package io.github.lumi.gametest.mixin;

import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a failed step from running dependent steps and replacing its first error. */
@Mixin(GameTestSequence.class)
abstract class GameTestSequenceFailureMixin {
    @Shadow @Final private GameTestInfo parent;

    @Inject(method = "tick", at = {
            @At("HEAD"),
            @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V", shift = At.Shift.AFTER)
    }, cancellable = true)
    private void stopCompletedSequence(int tick, CallbackInfo callback) {
        // fail() records the error before vanilla marks the test done on the next tick.
        if (parent.getError() != null || parent.isDone()) callback.cancel();
    }
}
