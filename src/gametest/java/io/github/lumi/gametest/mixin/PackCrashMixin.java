package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "io.github.lumi.storage.object.ObjectPack$Writer", remap = false)
public abstract class PackCrashMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private void staged(CallbackInfoReturnable<?> callback) {
        LumiPersistenceCrashProbe.hit("objects-staged");
    }
    @Inject(method = "publish", at = @At(value = "INVOKE",
            target = "Ljava/nio/channels/FileChannel;force(Z)V", shift = At.Shift.AFTER))
    private void forced(CallbackInfoReturnable<?> callback) {
        LumiPersistenceCrashProbe.hit("pack-forced");
    }
    @Inject(method = "publish", at = @At(value = "INVOKE",
            target = "Lio/github/lumi/storage/object/ObjectPack;moveAtomically(Ljava/nio/file/Path;Ljava/nio/file/Path;)V",
            shift = At.Shift.AFTER))
    private void moved(CallbackInfoReturnable<?> callback) {
        LumiPersistenceCrashProbe.hit("pack-published");
    }
    @Inject(method = "publish", at = @At("RETURN"))
    private void published(CallbackInfoReturnable<?> callback) {
        LumiPersistenceCrashProbe.hit("objects-published");
    }
}
