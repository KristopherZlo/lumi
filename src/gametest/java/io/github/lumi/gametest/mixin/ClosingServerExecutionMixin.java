package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiShutdownGameTests;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class ClosingServerExecutionMixin {
    @Inject(method = "scheduleExecutables", at = @At("HEAD"), cancellable = true)
    private void inlineOnClosingWorker(CallbackInfoReturnable<Boolean> callback) {
        if (LumiShutdownGameTests.isClosingWorker()) callback.setReturnValue(false);
    }

    @Inject(method = "isStopped", at = @At("HEAD"), cancellable = true)
    private void stoppedOnClosingWorker(CallbackInfoReturnable<Boolean> callback) {
        if (LumiShutdownGameTests.isClosingWorker()) callback.setReturnValue(true);
    }
}
