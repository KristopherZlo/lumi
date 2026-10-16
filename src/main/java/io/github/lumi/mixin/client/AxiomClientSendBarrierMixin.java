package io.github.lumi.mixin.client;

import com.moulberry.axiom.packets.AxiomServerboundPacket;
//? if >=26.1
/*import com.moulberry.axiom.AxiomClient;*/
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Also covers native entity requests and callbacks outside the main tool tick. */
//? if >=26.1 {
/*@Mixin(value = AxiomClient.class, remap = false)
abstract class AxiomClientSendBarrierMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private static void lumi$guardSend(AxiomServerboundPacket packet, CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused()) callback.cancel();
    }
}
*///?} else {
@Mixin(value = AxiomServerboundPacket.class, remap = false)
interface AxiomClientSendBarrierMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    default void lumi$guardSend(CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused()) callback.cancel();
    }
}
//?}
