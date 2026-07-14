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
        if (!LUMA_DIRECT_SECTION_CAPTURE.requiresInterception()) {
            return original.call(localX, localY, localZ, newState, lock);
        }
        LevelChunkSection section = (LevelChunkSection) (Object) this;
        if (LUMA_DIRECT_SECTION_CAPTURE.blocksWorldMutation(section)) {
            return section.getBlockState(localX, localY, localZ);
        }
        DirectSectionMutationCaptureService.PendingDirectSectionMutation mutation =
                LUMA_DIRECT_SECTION_CAPTURE.captureBefore(
                        section,
                        localX,
                        localY,
                        localZ,
                        newState
                );
        try {
            return original.call(localX, localY, localZ, newState, lock);
        } finally {
            LUMA_DIRECT_SECTION_CAPTURE.captureAfter(
                    section,
                    localX,
                    localY,
                    localZ,
                    mutation
            );
        }
    }
}
