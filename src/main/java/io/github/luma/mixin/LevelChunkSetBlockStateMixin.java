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
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkSetBlockStateMixin {

    @Unique
    private static final ExternalToolMutationOriginDetector LUMA_TOOL_DETECTOR =
            ExternalToolMutationOriginDetector.getInstance();

    @Unique
    private static final ThreadLocal<Deque<PendingExternalToolBlockMutation>> LUMA_PENDING_TOOL_MUTATIONS =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"))
    private void luma$captureBeforeAxiomChunkSetBlock(
            BlockPos pos,
            BlockState newState,
            int flags,
            CallbackInfoReturnable<BlockState> cir
    ) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (HistoryCaptureManager.shouldCaptureMutation(WorldMutationContext.currentSource())
                || WorldMutationCaptureGuard.isWithinLevelSetBlockBoundary()) {
            return;
        }
        if (WorldMutationContext.captureSuppressed()) {
            return;
        }

        var operation = LUMA_TOOL_DETECTOR.detectOperation();
        if (operation.isEmpty()) {
            return;
        }

        LevelChunk chunk = (LevelChunk) (Object) this;
        BlockState oldState = chunk.getBlockState(pos);
        CompoundTag oldBlockEntity = this.luma$blockEntityTag(serverLevel, chunk, pos, oldState);
        WorldMutationCaptureGuard.pushChunkSetBlockBoundary();
        LUMA_PENDING_TOOL_MUTATIONS.get().push(new PendingExternalToolBlockMutation(
                pos.immutable(),
                oldState,
                oldBlockEntity,
                operation.get()
        ));
    }

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void luma$captureAfterAxiomChunkSetBlock(
            BlockPos pos,
            BlockState newState,
            int flags,
            CallbackInfoReturnable<BlockState> cir
    ) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }

        Deque<PendingExternalToolBlockMutation> mutations = LUMA_PENDING_TOOL_MUTATIONS.get();
        if (mutations.isEmpty()) {
            return;
        }

        PendingExternalToolBlockMutation mutation = mutations.pop();
        boolean sourcePushed = false;
        try {
            if (cir.getReturnValue() == null) {
                return;
            }

            LevelChunk chunk = (LevelChunk) (Object) this;
            BlockState appliedState = chunk.getBlockState(mutation.pos());
            CompoundTag newBlockEntity = this.luma$blockEntityTag(serverLevel, chunk, mutation.pos(), appliedState);
            WorldMutationContext.pushExternalSource(
                    mutation.operation().source(),
                    mutation.operation().actor(),
                    mutation.operation().actionId()
            );
            sourcePushed = true;
            HistoryCaptureManager.getInstance().recordBlockChange(
                    serverLevel,
                    mutation.pos(),
                    mutation.oldState(),
                    appliedState,
                    mutation.oldBlockEntity(),
                    newBlockEntity
            );
        } finally {
            if (sourcePushed) {
                WorldMutationContext.popSource();
            }
            WorldMutationCaptureGuard.popChunkSetBlockBoundary();
        }
    }

    @Unique
    private CompoundTag luma$blockEntityTag(ServerLevel serverLevel, LevelChunk chunk, BlockPos pos, BlockState state) {
        if (state == null || !state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = chunk.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(serverLevel.registryAccess());
    }

    @Unique
    private record PendingExternalToolBlockMutation(
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            ObservedExternalToolOperation operation
    ) {
    }
}
