package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.axiom.ClientEvents;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Stops native ticks from starting more tool work while their existing workers drain. */
@Mixin(value = ClientEvents.class, remap = false)
abstract class AxiomClientTickBarrierMixin {
    @WrapOperation(method = "register", at = @At(value = "INVOKE",
            target = "Lnet/fabricmc/fabric/api/event/Event;register(Ljava/lang/Object;)V"))
    private static void lumi$guardTicks(Event<?> event, Object callback, Operation<Void> original) {
        if (callback instanceof ClientTickEvents.EndTick tick) {
            callback = (ClientTickEvents.EndTick) client -> { if (!AxiomClientWorkBarrier.isPaused()) tick.onEndTick(client); };
        } else if (callback instanceof ClientTickEvents.StartTick tick) {
            callback = (ClientTickEvents.StartTick) client -> { if (!AxiomClientWorkBarrier.isPaused()) tick.onStartTick(client); };
        }
        original.call(event, callback);
    }
}
