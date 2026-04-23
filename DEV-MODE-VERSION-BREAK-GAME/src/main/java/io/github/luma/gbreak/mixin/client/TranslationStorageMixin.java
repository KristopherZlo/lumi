package io.github.luma.gbreak.mixin.client;

import io.github.luma.gbreak.bug.GameBreakingBug;
import io.github.luma.gbreak.client.CorruptedTextGenerator;
import io.github.luma.gbreak.state.BugStateController;
import net.minecraft.client.resource.language.TranslationStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TranslationStorage.class)
abstract class TranslationStorageMixin {

    @Inject(method = "get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true)
    private void gbreak$corruptTranslationWithFallback(String key, String fallback, CallbackInfoReturnable<String> cir) {
        if (!BugStateController.getInstance().isActive(GameBreakingBug.GLOBAL_CORRUPTION)) {
            return;
        }

        cir.setReturnValue(CorruptedTextGenerator.corrupt(cir.getReturnValue()));
    }
}
