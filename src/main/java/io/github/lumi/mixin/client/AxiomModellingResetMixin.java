package io.github.lumi.mixin.client;

import com.moulberry.axiom.tools.modelling.ModellingTool;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import java.util.concurrent.Future;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native reset leaves this calculation observable by a later tool tick. */
@Mixin(value = ModellingTool.class, remap = false)
abstract class AxiomModellingResetMixin {
    @Shadow private Future<?> pendingCalculation;

    @Inject(method = "reset", at = @At("HEAD"))
    private void lumi$cancelCalculation(CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused() && pendingCalculation != null) {
            pendingCalculation.cancel(true);
            pendingCalculation = null;
        }
    }
}
