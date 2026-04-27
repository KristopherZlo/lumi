package io.github.luma.mixin;

import io.github.luma.minecraft.capture.DirectSectionMutationCaptureService;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
abstract class LevelChunkSectionSetBlockStateMixin {

    @Unique
    private static final DirectSectionMutationCaptureService LUMA_DIRECT_SECTION_CAPTURE =
            DirectSectionMutationCaptureService.getInstance();

    @Unique
    private static final ThreadLocal<Deque<DirectSectionMutationCaptureService.PendingDirectSectionMutation>> LUMA_PENDING_DIRECT_SECTION_MUTATIONS =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"))
    private void luma$captureBeforeDirectSectionSetBlock(
            int localX,
            int localY,
            int localZ,
            BlockState newState,
            boolean lock,
            CallbackInfoReturnable<BlockState> cir
    ) {
        DirectSectionMutationCaptureService.PendingDirectSectionMutation mutation =
                LUMA_DIRECT_SECTION_CAPTURE.captureBefore(
                        (LevelChunkSection) (Object) this,
                        localX,
                        localY,
                        localZ
                );
        if (mutation.operation() != null) {
            LUMA_PENDING_DIRECT_SECTION_MUTATIONS.get().push(mutation);
        }
    }

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void luma$captureAfterDirectSectionSetBlock(
            int localX,
            int localY,
            int localZ,
            BlockState newState,
            boolean lock,
            CallbackInfoReturnable<BlockState> cir
    ) {
        Deque<DirectSectionMutationCaptureService.PendingDirectSectionMutation> mutations =
                LUMA_PENDING_DIRECT_SECTION_MUTATIONS.get();
        if (mutations.isEmpty()) {
            return;
        }

        LUMA_DIRECT_SECTION_CAPTURE.captureAfter(
                (LevelChunkSection) (Object) this,
                localX,
                localY,
                localZ,
                mutations.pop()
        );
    }
}
