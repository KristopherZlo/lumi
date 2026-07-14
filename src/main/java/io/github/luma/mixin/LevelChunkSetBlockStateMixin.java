package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.integration.common.ExternalToolMutationSourceResolver;
import io.github.luma.integration.common.ObservedExternalToolOperation;
import io.github.luma.minecraft.capture.BlockEntitySnapshot;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationCaptureGuard;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.ExactReplayStateGuard;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.minecraft.world.WorldReplayTickSuppression;
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

@Mixin(LevelChunk.class)
abstract class LevelChunkSetBlockStateMixin {

    @Unique
    private static final ExternalToolMutationSourceResolver LUMA_TOOL_SOURCE_RESOLVER =
            ExternalToolMutationSourceResolver.getInstance();
    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();
    @Unique
    private static final ExactReplayStateGuard LUMA_EXACT_REPLAY_STATE_GUARD =
            ExactReplayStateGuard.getInstance();
    @Unique
    private static final WorldOperationManager LUMA_WORLD_OPERATIONS = WorldOperationManager.getInstance();

    @Shadow
    @Final
    Level level;

    @WrapMethod(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;")
    private BlockState luma$wrapChunkSetBlockState(
            BlockPos pos,
            BlockState newState,
            int flags,
            Operation<BlockState> original
    ) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return original.call(pos, newState, flags);
        }
        if (LUMA_WORLD_OPERATIONS.blocksWorldMutations(serverLevel)) {
            return null;
        }
        if (!WorldMutationContext.captureSuppressed()) {
            LUMA_EXACT_REPLAY_STATE_GUARD.releaseForExplicitMutation(
                    serverLevel,
                    WorldMutationContext.currentSource()
            );
        }
        if (LUMA_REPLAY_TICK_SUPPRESSION.shouldSuppressMutation(
                serverLevel,
                pos,
                WorldMutationContext.currentSource()
        )) {
            return null;
        }

        PendingBlockMutation mutation = this.luma$captureBeforeChunkSetBlock(serverLevel, pos, newState);
        if (mutation == null) {
            BlockState previous = original.call(pos, newState, flags);
            if (previous != null && !WorldMutationCaptureGuard.isWithinLevelSetBlockBoundary()) {
                HistoryCaptureManager.getInstance().recordPersistentBlockMutation(serverLevel, pos);
            }
            return previous;
        }

        try {
            BlockState previous = original.call(pos, newState, flags);
            if (previous != null) {
                HistoryCaptureManager.getInstance().recordPersistentBlockMutation(serverLevel, mutation.pos());
                LevelChunk chunk = (LevelChunk) (Object) this;
                BlockState appliedState = chunk.getBlockState(mutation.pos());
                CompoundTag newBlockEntity = this.luma$blockEntityTag(serverLevel, chunk, mutation.pos(), appliedState);
                if (mutation.operation() == null) {
                    HistoryCaptureManager.getInstance().recordBlockChange(
                            serverLevel,
                            mutation.pos(),
                            mutation.oldState(),
                            appliedState,
                            mutation.oldBlockEntity(),
                            newBlockEntity
                    );
                } else {
                    boolean accessAllowed = mutation.operation().accessAllowed()
                            || !serverLevel.getServer().isDedicatedServer();
                    try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                            mutation.operation().source(),
                            mutation.operation().actor(),
                            mutation.operation().actionId(),
                            accessAllowed
                    )) {
                        HistoryCaptureManager.getInstance().recordBlockChange(
                                serverLevel,
                                mutation.pos(),
                                mutation.oldState(),
                                appliedState,
                                mutation.oldBlockEntity(),
                                newBlockEntity
                        );
                    }
                }
            }
            return previous;
        } finally {
            mutation.boundary().close();
        }
    }

    @Unique
    private PendingBlockMutation luma$captureBeforeChunkSetBlock(ServerLevel serverLevel, BlockPos pos, BlockState newState) {
        if (WorldMutationCaptureGuard.isWithinLevelSetBlockBoundary()) {
            return null;
        }

        var currentSource = WorldMutationContext.currentSource();
        boolean captureSuppressed = WorldMutationContext.captureSuppressed();
        boolean currentSourceCaptures = HistoryCaptureManager.shouldCaptureMutation(currentSource);
        ObservedExternalToolOperation operation = currentSourceCaptures
                ? LUMA_TOOL_SOURCE_RESOLVER.detectPlayerSourceOverride(currentSource, captureSuppressed).orElse(null)
                : LUMA_TOOL_SOURCE_RESOLVER.detectUnattributedOperation(captureSuppressed).orElse(null);
        if (operation != null && currentSourceCaptures) {
            operation = operation.withAccessAllowed(WorldMutationContext.currentAccessAllowed());
        }

        LevelChunk chunk = (LevelChunk) (Object) this;
        BlockState oldState = chunk.getBlockState(pos);
        CompoundTag oldBlockEntity = this.luma$blockEntityTag(serverLevel, chunk, pos, oldState);
        if (!oldState.equals(newState)) {
            this.luma$capturePreMutationBaseline(serverLevel, pos, oldState, oldBlockEntity, operation, currentSourceCaptures);
        }
        if (operation == null && !currentSourceCaptures) {
            return null;
        }
        WorldMutationCaptureGuard.CaptureBoundary boundary = WorldMutationCaptureGuard.pushChunkSetBlockBoundary();
        return new PendingBlockMutation(
                pos.immutable(),
                oldState,
                oldBlockEntity,
                operation,
                boundary
        );
    }

    @Unique
    private CompoundTag luma$blockEntityTag(ServerLevel serverLevel, LevelChunk chunk, BlockPos pos, BlockState state) {
        if (state == null || !state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = chunk.getBlockEntity(pos);
        return BlockEntitySnapshot.capture(serverLevel, blockEntity);
    }

    @Unique
    private void luma$capturePreMutationBaseline(
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            ObservedExternalToolOperation operation,
            boolean currentSourceCaptures
    ) {
        if (operation == null || currentSourceCaptures) {
            HistoryCaptureManager.getInstance().capturePreMutationBaseline(level, pos, oldState, oldBlockEntity);
            return;
        }
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                operation.source(), operation.actor(), operation.actionId(), operation.accessAllowed()
        )) {
            HistoryCaptureManager.getInstance().capturePreMutationBaseline(level, pos, oldState, oldBlockEntity);
        }
    }

    @Unique
    private record PendingBlockMutation(
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            ObservedExternalToolOperation operation,
            WorldMutationCaptureGuard.CaptureBoundary boundary
    ) {
    }
}
