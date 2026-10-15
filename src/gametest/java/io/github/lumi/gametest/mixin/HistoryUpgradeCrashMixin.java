package io.github.lumi.gametest.mixin;

import io.github.lumi.domain.service.HistoryGenerationConversionService;
import io.github.lumi.gametest.LumiUpgradeCrashProbe;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = HistoryGenerationConversionService.class, remap = false)
public abstract class HistoryUpgradeCrashMixin {
    @ModifyVariable(method = "convert", at = @At("HEAD"), argsOnly = true)
    private Consumer<HistoryGenerationConversionService.Progress> cutAfterProgress(
            Consumer<HistoryGenerationConversionService.Progress> observer) {
        return progress -> {
            observer.accept(progress);
            LumiUpgradeCrashProbe.hit(progress.phase());
        };
    }
}
