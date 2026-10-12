package io.github.lumi.gametest.mixin;

import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.gametest.LumiPersistenceCrashProbe;
import io.github.lumi.storage.repository.OperationJournalRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = OperationJournalRepository.class, remap = false)
public abstract class JournalCrashMixin {
    @Inject(method = "create", at = @At("RETURN"))
    private void created(CallbackInfoReturnable<OperationJournal> callback) {
        LumiPersistenceCrashProbe.hit("journal-created");
    }
    @Inject(method = "advance", at = @At("RETURN"))
    private void advanced(OperationJournal expected, OperationPhase next,
            CallbackInfoReturnable<OperationJournal> callback) {
        if (next == OperationPhase.COMPLETE) LumiPersistenceCrashProbe.hit("journal-complete");
    }
    @Inject(method = "clear", at = @At("RETURN"))
    private void cleared(CallbackInfo callback) { LumiPersistenceCrashProbe.hit("cleanup"); }
}
