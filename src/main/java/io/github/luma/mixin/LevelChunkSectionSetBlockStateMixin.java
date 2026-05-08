package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.DirectSectionMutationCaptureService;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelChunkSection.class)
abstract class LevelChunkSectionSetBlockStateMixin {

    @Unique
    private static final DirectSectionMutationCaptureService LUMA_DIRECT_SECTION_CAPTURE =
            DirectSectionMutationCaptureService.getInstance();

    @WrapMethod(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;")
    private BlockState luma$wrapDirectSectionSetBlock(
            int localX,
            int localY,
            int localZ,
            BlockState newState,
            boolean lock,
            Operation<BlockState> original
    ) {
        DirectSectionMutationCaptureService.PendingDirectSectionMutation mutation =
                LUMA_DIRECT_SECTION_CAPTURE.captureBefore(
                        (LevelChunkSection) (Object) this,
                        localX,
                        localY,
                        localZ,
                        newState
                );
        BlockState previous = original.call(localX, localY, localZ, newState, lock);
        LUMA_DIRECT_SECTION_CAPTURE.captureAfter(
                (LevelChunkSection) (Object) this,
                localX,
                localY,
                localZ,
                mutation
        );
        return previous;
    }
}
