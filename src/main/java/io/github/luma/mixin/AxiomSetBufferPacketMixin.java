package io.github.luma.mixin;

import io.github.luma.integration.axiom.AxiomBlockBufferCaptureService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBuffer", remap = false)
abstract class AxiomSetBufferPacketMixin {

    @Inject(
            method = "applyBlockBufferServer",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private static void luma$captureAxiomBlockBuffer(
            @Coerce Object blockBuffer,
            @Coerce Object level,
            @Coerce Object changedRegion,
            @Coerce Object player,
            CallbackInfo ci
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        ServerPlayer serverPlayer = player instanceof ServerPlayer typedPlayer ? typedPlayer : null;
        AxiomBlockBufferCaptureService.getInstance().captureBeforeApply(blockBuffer, serverLevel, serverPlayer);
    }
}
