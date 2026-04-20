package io.github.luma.mixin;

import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
abstract class LevelSetBlockMixin {

    @Unique
    private static final ThreadLocal<Deque<PendingBlockMutation>> LUMA_PENDING_MUTATIONS = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void luma$captureBeforeSetBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (WorldMutationContext.currentSource() != io.github.luma.domain.model.WorldMutationSource.PLAYER) {
            return;
        }

        BlockState oldState = serverLevel.getBlockState(pos);
        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        CompoundTag oldBlockEntity = blockEntity == null ? null : blockEntity.saveWithFullMetadata(serverLevel.registryAccess());
        LUMA_PENDING_MUTATIONS.get().push(new PendingBlockMutation(pos.immutable(), oldState, oldBlockEntity));
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void luma$captureAfterSetBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (WorldMutationContext.currentSource() != io.github.luma.domain.model.WorldMutationSource.PLAYER) {
            return;
        }

        Deque<PendingBlockMutation> mutations = LUMA_PENDING_MUTATIONS.get();
        if (mutations.isEmpty()) {
            return;
        }

        PendingBlockMutation mutation = mutations.pop();
        if (!cir.getReturnValue()) {
            return;
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(mutation.pos());
        CompoundTag newBlockEntity = blockEntity == null ? null : blockEntity.saveWithFullMetadata(serverLevel.registryAccess());
        HistoryCaptureManager.getInstance().recordBlockChange(
                serverLevel,
                mutation.pos(),
                mutation.oldState(),
                serverLevel.getBlockState(mutation.pos()),
                mutation.oldBlockEntity(),
                newBlockEntity
        );
    }

    @Unique
    private record PendingBlockMutation(BlockPos pos, BlockState oldState, CompoundTag oldBlockEntity) {
    }
}
