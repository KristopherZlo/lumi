package io.github.lumi.gametest.mixin;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the integrated server able to receive halt while Fabric closes its world. */
@Mixin(targets = "net.fabricmc.fabric.impl.client.gametest.context.TestSingleplayerContextImpl", remap = false)
public abstract class ClientGameTestWorldCloseMixin {
    @Shadow @Final private ClientGameTestContext context;

    @Inject(method = "close", at = @At("HEAD"))
    private void lumi$drainUntilHalt(CallbackInfo callback) {
        context.runOnClient(client -> {
            var server = client.getSingleplayerServer();
            if (server != null) server.execute(() -> server.managedBlock(() -> !server.isRunning()));
        });
    }
}
