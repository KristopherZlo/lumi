package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.integration.axiom.AxiomNativeUndoRedoGuard;
import io.github.luma.integration.axiom.AxiomSetBlockPacketCaptureService;
import io.github.luma.minecraft.capture.WorldMutationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBlock", remap = false)
abstract class AxiomSetBlockPacketMixin {

    @WrapMethod(
            method = "handle",
            remap = false,
            require = 0
    )
    private void luma$wrapAxiomSetBlock(
            @Coerce Object server,
            @Coerce Object player,
            Operation<Void> original
    ) {
        WorldMutationContext.SuppressionFrame nativeReplaySuppression = null;
        boolean nativeUndoRedoReplay = AxiomNativeUndoRedoGuard.consumeExpectedNativeReplay();
        try {
            if (nativeUndoRedoReplay) {
                nativeReplaySuppression = WorldMutationContext.pushCaptureSuppression();
                original.call(server, player);
                return;
            }

            try (WorldMutationContext.SourceFrame ignored =
                         AxiomSetBlockPacketCaptureService.getInstance().pushPacketSource(this, player)) {
                original.call(server, player);
            }
        } finally {
            if (nativeReplaySuppression != null) {
                nativeReplaySuppression.close();
            }
        }
    }
}
