package io.github.luma.gbreak.mixin.client;

import io.github.luma.gbreak.bug.GameBreakingBug;
import io.github.luma.gbreak.client.CorruptedTextGenerator;
import io.github.luma.gbreak.state.BugStateController;
import net.minecraft.util.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Language.class)
abstract class LanguageMixin {

    @Inject(method = "get(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true)
    private void gbreak$corruptTranslation(String key, CallbackInfoReturnable<String> cir) {
        if (!BugStateController.getInstance().isActive(GameBreakingBug.GLOBAL_CORRUPTION)) {
            return;
        }

        cir.setReturnValue(CorruptedTextGenerator.corrupt(cir.getReturnValue()));
    }
}
