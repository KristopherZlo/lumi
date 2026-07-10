package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.integration.axiom.AxiomNativeUndoRedoGuard;
import io.github.luma.integration.axiom.AxiomSetBlockPacketCaptureService;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBlock", remap = false)
abstract class AxiomSetBlockPacketMixin {

    @Shadow(remap = false)
    @Final
    private Map<BlockPos, BlockState> blocks;

    @Shadow(remap = false)
    @Final
    private int reason;

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

            AxiomSetBlockPacketCaptureService captureService = AxiomSetBlockPacketCaptureService.getInstance();
            try (WorldMutationContext.SourceFrame ignored = captureService.pushPacketSource(player)) {
                AxiomSetBlockPacketCaptureService.PendingPacketCapture packetCapture =
                        captureService.captureBefore(player, this.blocks, this.reason);
                original.call(server, player);
                captureService.captureAfter(packetCapture);
            }
        } finally {
            if (nativeReplaySuppression != null) {
                nativeReplaySuppression.close();
            }
        }
    }
}
