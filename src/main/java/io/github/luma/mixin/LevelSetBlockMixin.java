package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.integration.common.ExternalToolMutationSourceResolver;
import io.github.luma.integration.common.ObservedExternalToolOperation;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationCaptureGuard;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.ExactReplayStateGuard;
import io.github.luma.minecraft.world.WorldReplayTickSuppression;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Level.class)
abstract class LevelSetBlockMixin {

    @Unique
    private static final ExternalToolMutationSourceResolver LUMA_TOOL_SOURCE_RESOLVER =
            ExternalToolMutationSourceResolver.getInstance();
    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();
    @Unique
    private static final ExactReplayStateGuard LUMA_EXACT_REPLAY_STATE_GUARD =
            ExactReplayStateGuard.getInstance();

    @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean luma$wrapSetBlock(
            BlockPos pos,
            BlockState newState,
            int flags,
            int recursionLeft,
            Operation<Boolean> original
    ) {
        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel serverLevel)) {
            return original.call(pos, newState, flags, recursionLeft);
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
            return false;
        }

        PendingBlockMutation mutation = this.luma$captureBeforeSetBlock(serverLevel, pos, newState);
        if (mutation == null) {
            return original.call(pos, newState, flags, recursionLeft);
        }

        try {
            boolean changed = original.call(pos, newState, flags, recursionLeft);
            if (changed) {
                BlockState appliedState = serverLevel.getBlockState(mutation.pos());
                CompoundTag newBlockEntity = this.luma$blockEntityTag(serverLevel, mutation.pos(), appliedState);
                this.luma$recordMutation(serverLevel, mutation, appliedState, newBlockEntity);
            }
            return changed;
        } finally {
            mutation.boundary().close();
        }
    }

    @Unique
    private PendingBlockMutation luma$captureBeforeSetBlock(ServerLevel serverLevel, BlockPos pos, BlockState newState) {
        var currentSource = WorldMutationContext.currentSource();
        boolean captureSuppressed = WorldMutationContext.captureSuppressed();
        boolean currentSourceCaptures = HistoryCaptureManager.shouldCaptureMutation(currentSource);
        ObservedExternalToolOperation operation = currentSourceCaptures
                ? LUMA_TOOL_SOURCE_RESOLVER.detectPlayerSourceOverride(currentSource, captureSuppressed).orElse(null)
                : LUMA_TOOL_SOURCE_RESOLVER.detectUnattributedOperation(captureSuppressed).orElse(null);
        if (operation != null && currentSourceCaptures) {
            operation = operation.withAccessAllowed(WorldMutationContext.currentAccessAllowed());
        }
        if (!currentSourceCaptures && operation == null) {
            return null;
        }

        BlockState oldState = serverLevel.getBlockState(pos);
        CompoundTag oldBlockEntity = this.luma$blockEntityTag(serverLevel, pos, oldState);
        if (currentSourceCaptures && !oldState.equals(newState)) {
            HistoryCaptureManager.getInstance().capturePreMutationBaseline(
                    serverLevel,
                    pos,
                    oldState,
                    oldBlockEntity
            );
        }
        WorldMutationCaptureGuard.CaptureBoundary boundary = WorldMutationCaptureGuard.pushLevelSetBlockBoundary();
        return new PendingBlockMutation(
                pos.immutable(),
                oldState,
                oldBlockEntity,
                operation,
                boundary
        );
    }

    @Unique
    private void luma$recordMutation(
            ServerLevel serverLevel,
            PendingBlockMutation mutation,
            BlockState appliedState,
            CompoundTag newBlockEntity
    ) {
        ObservedExternalToolOperation operation = mutation.operation();
        if (operation == null) {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    serverLevel,
                    mutation.pos(),
                    mutation.oldState(),
                    appliedState,
                    mutation.oldBlockEntity(),
                    newBlockEntity
            );
            return;
        }

        boolean accessAllowed = operation.accessAllowed() || !serverLevel.getServer().isDedicatedServer();
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                operation.source(),
                operation.actor(),
                operation.actionId(),
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

    @Unique
    private CompoundTag luma$blockEntityTag(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (state == null || !state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
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
