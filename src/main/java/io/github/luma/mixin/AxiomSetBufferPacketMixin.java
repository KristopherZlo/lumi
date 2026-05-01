package io.github.luma.mixin;

import io.github.luma.integration.axiom.AxiomBlockBufferCaptureService;
import io.github.luma.integration.axiom.AxiomNativeUndoRedoGuard;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.capture.WorldMutationCaptureGuard;
import java.util.ArrayDeque;
import java.util.Deque;
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
    private static final ThreadLocal<Deque<WorldMutationCaptureGuard.CaptureBoundary>> LUMA_DIRECT_SECTION_SUPPRESSIONS =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Unique
    private static final ThreadLocal<Deque<WorldMutationContext.SuppressionFrame>> LUMA_NATIVE_REPLAY_SUPPRESSIONS =
            ThreadLocal.withInitial(ArrayDeque::new);

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
        LUMA_DIRECT_SECTION_SUPPRESSIONS.get().push(WorldMutationCaptureGuard.pushDirectSectionCaptureSuppression());
        boolean nativeUndoRedoReplay = AxiomNativeUndoRedoGuard.consumeExpectedNativeReplay();
        if (nativeUndoRedoReplay) {
            LUMA_NATIVE_REPLAY_SUPPRESSIONS.get().push(WorldMutationContext.pushCaptureSuppression());
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
        Deque<WorldMutationContext.SuppressionFrame> suppressions = LUMA_NATIVE_REPLAY_SUPPRESSIONS.get();
        if (!suppressions.isEmpty()) {
            suppressions.pop().close();
        }
        if (suppressions.isEmpty()) {
            LUMA_NATIVE_REPLAY_SUPPRESSIONS.remove();
        }

        Deque<WorldMutationCaptureGuard.CaptureBoundary> boundaries = LUMA_DIRECT_SECTION_SUPPRESSIONS.get();
        if (!boundaries.isEmpty()) {
            boundaries.pop().close();
        }
        if (boundaries.isEmpty()) {
            LUMA_DIRECT_SECTION_SUPPRESSIONS.remove();
        }
    }
}
