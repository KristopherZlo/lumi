package io.github.lumi.gametest.mixin;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.gametest.LumiAxiomVerificationFault;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecraftSectionCapture.class, remap = false)
public abstract class AxiomVerificationFaultMixin {
    @Inject(method = "capture", at = @At("HEAD"))
    private void lumi$failCapture(CallbackInfoReturnable<SectionBlob> callback) throws IOException {
        LumiAxiomVerificationFault.beforeCapture();
    }
}
