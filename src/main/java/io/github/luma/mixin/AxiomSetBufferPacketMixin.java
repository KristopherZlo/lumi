package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.integration.axiom.AxiomBlockBufferCaptureService;
import io.github.luma.integration.axiom.AxiomNativeUndoRedoGuard;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.capture.WorldMutationCaptureGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBuffer", remap = false)
abstract class AxiomSetBufferPacketMixin {

    @WrapMethod(
            method = "applyBlockBufferServer",
            remap = false,
            require = 0
    )
    private static void luma$wrapAxiomBlockBuffer(
            @Coerce Object blockBuffer,
            @Coerce Object level,
            @Coerce Object changedRegion,
            @Coerce Object player,
            Operation<Void> original
    ) {
        WorldMutationCaptureGuard.CaptureBoundary directSectionSuppression = null;
        WorldMutationContext.SuppressionFrame nativeReplaySuppression = null;
        WorldMutationContext.SourceFrame axiomSourceFrame = null;
        AxiomBlockBufferCaptureService.PreparedCapture preparedCapture = null;
        boolean nativeUndoRedoReplay = AxiomNativeUndoRedoGuard.consumeExpectedNativeReplay();
        try {
            if (nativeUndoRedoReplay) {
                nativeReplaySuppression = WorldMutationContext.pushCaptureSuppression();
                directSectionSuppression = WorldMutationCaptureGuard.pushDirectSectionCaptureSuppression();
            } else if (level instanceof ServerLevel serverLevel) {
                ServerPlayer serverPlayer = player instanceof ServerPlayer typedPlayer ? typedPlayer : null;
                preparedCapture = AxiomBlockBufferCaptureService.getInstance().prepareBeforeApply(
                        blockBuffer,
                        serverLevel,
                        serverPlayer
                );
                if (preparedCapture.hasSourceContext()) {
                    axiomSourceFrame = WorldMutationContext.pushExternalSource(
                            WorldMutationSource.AXIOM,
                            preparedCapture.actor(),
                            preparedCapture.actionId(),
                            preparedCapture.accessAllowed()
                    );
                }
            }
            original.call(blockBuffer, level, changedRegion, player);
            AxiomBlockBufferCaptureService.getInstance().recordAfterApply(preparedCapture);
        } finally {
            if (axiomSourceFrame != null) {
                axiomSourceFrame.close();
            }
            if (nativeReplaySuppression != null) {
                nativeReplaySuppression.close();
            }
            if (directSectionSuppression != null) {
                directSectionSuppression.close();
            }
        }
    }
}
