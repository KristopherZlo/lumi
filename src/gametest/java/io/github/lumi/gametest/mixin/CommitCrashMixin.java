package io.github.lumi.gametest.mixin;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import io.github.lumi.storage.repository.CommitRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CommitRepository.class, remap = false)
public abstract class CommitCrashMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private void written(Commit commit, CallbackInfoReturnable<CommitId> callback) {
        LumiPersistenceCrashProbe.commitWritten(commit, callback.getReturnValue());
    }
}
