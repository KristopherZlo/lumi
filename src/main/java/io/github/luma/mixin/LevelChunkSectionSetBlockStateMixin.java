package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.ChunkSectionOwnerAccess;
import io.github.luma.minecraft.capture.ChunkSectionOwnershipRegistry;
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
        LevelChunkSection section = (LevelChunkSection) (Object) this;
        ChunkSectionOwnershipRegistry.SectionOwner owner =
                ((ChunkSectionOwnerAccess) (Object) this).luma$getOwner();
        if (owner == null || !LUMA_DIRECT_SECTION_CAPTURE.requiresInterception()) {
            return original.call(localX, localY, localZ, newState, lock);
        }
        DirectSectionMutationCaptureService.PendingDirectSectionMutation mutation =
                LUMA_DIRECT_SECTION_CAPTURE.captureBefore(
                        section,
                        owner,
                        localX,
                        localY,
                        localZ,
                        newState
                );
        if (mutation.blocked()) {
            return mutation.oldState();
        }
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
