package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "io.github.lumi.storage.object.ObjectPackIndex", remap = false)
public abstract class IndexCrashMixin {
    @Inject(method = "write", at = @At(value = "INVOKE",
            target = "Ljava/nio/channels/FileChannel;force(Z)V", shift = At.Shift.AFTER))
    private static void forced(CallbackInfo callback) { LumiPersistenceCrashProbe.hit("index-forced"); }
}
