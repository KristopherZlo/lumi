package io.github.lumi.mixin.client;

import com.moulberry.axiom.packets.AxiomServerboundPacket;
//? if >=26.1
/*import com.moulberry.axiom.AxiomClient;*/
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import io.github.lumi.client.integration.AxiomHistoryCaptureScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Also covers native entity requests and callbacks outside the main tool tick. */
//? if >=26.1 {
/*@Mixin(value = AxiomClient.class, remap = false)
abstract class AxiomClientSendBarrierMixin {
    @WrapMethod(method = "send")
    private static void lumi$scopeSend(AxiomServerboundPacket packet, Operation<Void> original) {
        AxiomHistoryCaptureScope.send(packet, () -> original.call(packet));
    }

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private static void lumi$guardSend(AxiomServerboundPacket packet, CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused()) callback.cancel();
    }
}
*///?} else {
@Mixin(value = AxiomServerboundPacket.class, remap = false)
interface AxiomClientSendBarrierMixin {
    @WrapMethod(method = "send")
    default void lumi$scopeSend(Operation<Void> original) {
        AxiomHistoryCaptureScope.send((AxiomServerboundPacket) this, () -> original.call());
    }

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    default void lumi$guardSend(CallbackInfo callback) {
        if (AxiomClientWorkBarrier.isPaused()) callback.cancel();
    }
}
//?}
