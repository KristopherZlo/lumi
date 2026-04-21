package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FallingBlockEntity.class)
abstract class FallingBlockEntityMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void luma$captureLandingBlockEntity(CallbackInfo ci) {
        FallingBlockEntity entity = (FallingBlockEntity) (Object) this;
        if (!entity.isRemoved() || entity.blockData == null || !entity.getBlockState().hasBlockEntity()) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        BlockPos pos = entity.blockPosition();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        CompoundTag newBlockEntity = blockEntity.saveWithFullMetadata(level.registryAccess());
        WorldMutationContext.runWithSource(WorldMutationSource.FALLING_BLOCK, () -> HistoryCaptureManager.getInstance().recordBlockChange(
                level,
                pos,
                state,
                state,
                null,
                newBlockEntity
        ));
    }
}
