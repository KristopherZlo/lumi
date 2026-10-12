package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "io.github.lumi.storage.repository.AtomicFileWriter", remap = false)
public abstract class AtomicRefCrashMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private static void forced(Path temporary, byte[] content, CallbackInfo callback) {
        LumiPersistenceCrashProbe.refForced(temporary);
    }
}
