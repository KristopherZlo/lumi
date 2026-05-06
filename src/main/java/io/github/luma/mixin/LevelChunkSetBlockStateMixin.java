package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.integration.common.ExternalToolMutationSourceResolver;
import io.github.luma.integration.common.ObservedExternalToolOperation;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationCaptureGuard;
import io.github.luma.minecraft.capture.WorldMutationContext;
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

        PendingBlockMutation mutation = this.luma$captureBeforeChunkSetBlock(serverLevel, pos);
        if (mutation == null) {
            return original.call(pos, newState, flags);
        }

        try {
            BlockState previous = original.call(pos, newState, flags);
            if (previous != null) {
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
    private PendingBlockMutation luma$captureBeforeChunkSetBlock(ServerLevel serverLevel, BlockPos pos) {
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
        if (operation == null && !currentSourceCaptures) {
            return null;
        }

        LevelChunk chunk = (LevelChunk) (Object) this;
        BlockState oldState = chunk.getBlockState(pos);
        CompoundTag oldBlockEntity = this.luma$blockEntityTag(serverLevel, chunk, pos, oldState);
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
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(serverLevel.registryAccess());
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
