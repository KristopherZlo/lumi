package io.github.luma.mixin;

import io.github.luma.integration.common.ExternalToolMutationOriginDetector;
import io.github.luma.integration.common.ObservedExternalToolOperation;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationCaptureGuard;
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
    private static final ExternalToolMutationOriginDetector LUMA_TOOL_DETECTOR =
            ExternalToolMutationOriginDetector.getInstance();

    @Unique
    private static final ThreadLocal<Deque<PendingBlockMutation>> LUMA_PENDING_MUTATIONS = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void luma$captureBeforeSetBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        ObservedExternalToolOperation operation = null;
        boolean currentSourceCaptures = HistoryCaptureManager.shouldCaptureMutation(WorldMutationContext.currentSource());
        if (!currentSourceCaptures) {
            var detectedOperation = LUMA_TOOL_DETECTOR.detectOperation();
            if (detectedOperation.isEmpty()) {
                return;
            }
            operation = detectedOperation.get();
        }

        BlockState oldState = serverLevel.getBlockState(pos);
        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        CompoundTag oldBlockEntity = blockEntity == null ? null : blockEntity.saveWithFullMetadata(serverLevel.registryAccess());
        WorldMutationCaptureGuard.pushLevelSetBlockBoundary();
        LUMA_PENDING_MUTATIONS.get().push(new PendingBlockMutation(
                pos.immutable(),
                oldState,
                oldBlockEntity,
                operation
        ));
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void luma$captureAfterSetBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        Deque<PendingBlockMutation> mutations = LUMA_PENDING_MUTATIONS.get();
        if (mutations.isEmpty()) {
            return;
        }

        PendingBlockMutation mutation = mutations.pop();
        try {
            if (!cir.getReturnValue()) {
                return;
            }

            BlockEntity blockEntity = serverLevel.getBlockEntity(mutation.pos());
            CompoundTag newBlockEntity = blockEntity == null ? null : blockEntity.saveWithFullMetadata(serverLevel.registryAccess());
            this.luma$recordMutation(serverLevel, mutation, newBlockEntity);
        } finally {
            WorldMutationCaptureGuard.popLevelSetBlockBoundary();
        }
    }

    @Unique
    private void luma$recordMutation(ServerLevel serverLevel, PendingBlockMutation mutation, CompoundTag newBlockEntity) {
        ObservedExternalToolOperation operation = mutation.operation();
        if (operation == null) {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    serverLevel,
                    mutation.pos(),
                    mutation.oldState(),
                    serverLevel.getBlockState(mutation.pos()),
                    mutation.oldBlockEntity(),
                    newBlockEntity
            );
            return;
        }

        WorldMutationContext.pushExternalSource(operation.source(), operation.actor(), operation.actionId());
        try {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    serverLevel,
                    mutation.pos(),
                    mutation.oldState(),
                    serverLevel.getBlockState(mutation.pos()),
                    mutation.oldBlockEntity(),
                    newBlockEntity
            );
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Unique
    private record PendingBlockMutation(
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            ObservedExternalToolOperation operation
    ) {
    }
}
