package io.github.luma.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
abstract class ChunkAccessBlockEntityNbtMixin {

    @Inject(method = "setBlockEntityNbt", at = @At("HEAD"), cancellable = true)
    private void luma$rejectOrphanedBlockEntityNbt(CompoundTag tag, CallbackInfo callback) {
        ChunkAccess chunk = (ChunkAccess) (Object) this;
        BlockPos pos = BlockEntity.getPosFromTag(chunk.getPos(), tag);
        if (!chunk.getBlockState(pos).hasBlockEntity()) {
            callback.cancel();
        }
    }
}
