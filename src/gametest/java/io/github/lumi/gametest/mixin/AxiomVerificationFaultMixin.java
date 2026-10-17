package io.github.lumi.gametest.mixin;

import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.gametest.LumiAxiomVerificationFault;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WorldObjectRepository.class, remap = false)
public abstract class AxiomVerificationFaultMixin {
    @Inject(method = "identify", at = @At("HEAD"))
    private void lumi$failIdentification(SectionBlob section, CallbackInfoReturnable<ObjectId> callback) throws IOException {
        LumiAxiomVerificationFault.beforeIdentification();
    }
}
