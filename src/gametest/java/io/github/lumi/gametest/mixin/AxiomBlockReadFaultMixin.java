package io.github.lumi.gametest.mixin;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.gametest.LumiAxiomVerificationFault;
import io.github.lumi.minecraft.world.MinecraftLiveBlockWorldAccess;
import java.io.IOException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecraftLiveBlockWorldAccess.class, remap = false)
public abstract class AxiomBlockReadFaultMixin {
    @Inject(method = "read(Lio/github/lumi/domain/model/BlockPosition;)Lio/github/lumi/domain/model/BlockSnapshot;",
            at = @At("HEAD"))
    private void lumi$failBlockRead(BlockPosition position, CallbackInfoReturnable<BlockSnapshot> callback)
            throws IOException {
        LumiAxiomVerificationFault.beforeBlockRead();
    }
}
