package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import io.github.lumi.storage.repository.BranchRefRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BranchRefRepository.class, remap = false)
public abstract class RefCrashMixin {
    @Inject(method = {"compareAndSet", "create"}, at = @At("RETURN"))
    private void published(CallbackInfoReturnable<?> callback) { LumiPersistenceCrashProbe.hit("ref-published"); }
}
