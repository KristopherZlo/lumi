package io.github.luma.mixin;

import io.github.luma.integration.axiom.AxiomBlockBufferCaptureService;
import io.github.luma.integration.axiom.AxiomNativeUndoRedoGuard;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.capture.WorldMutationCaptureGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBuffer", remap = false)
abstract class AxiomSetBufferPacketMixin {

    @Unique
    private static final ThreadLocal<Integer> LUMA_NATIVE_REPLAY_SUPPRESSION_DEPTH = ThreadLocal.withInitial(() -> 0);

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
        WorldMutationCaptureGuard.pushDirectSectionCaptureSuppression();
        boolean nativeUndoRedoReplay = AxiomNativeUndoRedoGuard.consumeExpectedNativeReplay();
        if (nativeUndoRedoReplay) {
            WorldMutationContext.pushCaptureSuppression();
            LUMA_NATIVE_REPLAY_SUPPRESSION_DEPTH.set(LUMA_NATIVE_REPLAY_SUPPRESSION_DEPTH.get() + 1);
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (nativeUndoRedoReplay) {
            return;
        }

        ServerPlayer serverPlayer = player instanceof ServerPlayer typedPlayer ? typedPlayer : null;
        AxiomBlockBufferCaptureService.getInstance().captureBeforeApply(blockBuffer, serverLevel, serverPlayer);
    }

    @Inject(
            method = "applyBlockBufferServer",
            at = @At("RETURN"),
            remap = false,
            require = 0
    )
    private static void luma$releaseAxiomBlockBufferCaptureSuppression(
            @Coerce Object blockBuffer,
            @Coerce Object level,
            @Coerce Object changedRegion,
            @Coerce Object player,
            CallbackInfo ci
    ) {
        int suppressionDepth = LUMA_NATIVE_REPLAY_SUPPRESSION_DEPTH.get();
        if (suppressionDepth > 0) {
            WorldMutationContext.popCaptureSuppression();
            if (suppressionDepth == 1) {
                LUMA_NATIVE_REPLAY_SUPPRESSION_DEPTH.remove();
            } else {
                LUMA_NATIVE_REPLAY_SUPPRESSION_DEPTH.set(suppressionDepth - 1);
            }
        }
        WorldMutationCaptureGuard.popDirectSectionCaptureSuppression();
    }
}
