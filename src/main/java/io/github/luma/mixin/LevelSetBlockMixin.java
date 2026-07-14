package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.integration.common.ExternalToolMutationSourceResolver;
import io.github.luma.integration.common.ObservedExternalToolOperation;
import io.github.luma.minecraft.capture.BlockEntitySnapshot;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.PostCallbackBlockMutationPolicy;
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
    @Unique
    private static final PostCallbackBlockMutationPolicy LUMA_POST_CALLBACK_MUTATION_POLICY =
            new PostCallbackBlockMutationPolicy();
    @Unique
    private static final WorldOperationManager LUMA_WORLD_OPERATIONS = WorldOperationManager.getInstance();

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
        if (LUMA_WORLD_OPERATIONS.blocksWorldMutations(serverLevel)) {
            return false;
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
            boolean changed = original.call(pos, newState, flags, recursionLeft);
            if (changed) {
                HistoryCaptureManager.getInstance().recordPersistentBlockMutation(serverLevel, pos);
            }
            return changed;
        }

        try {
            boolean changed = original.call(pos, newState, flags, recursionLeft);
            if (changed) {
                HistoryCaptureManager.getInstance().recordPersistentBlockMutation(serverLevel, mutation.pos());
                BlockState appliedState = serverLevel.getBlockState(mutation.pos());
                CompoundTag newBlockEntity = this.luma$blockEntityTag(serverLevel, mutation.pos(), appliedState);
                this.luma$recordMutation(serverLevel, mutation, newState, appliedState, newBlockEntity);
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

        BlockState oldState = serverLevel.getBlockState(pos);
        CompoundTag oldBlockEntity = this.luma$blockEntityTag(serverLevel, pos, oldState);
        if (!oldState.equals(newState)) {
            this.luma$capturePreMutationBaseline(serverLevel, pos, oldState, oldBlockEntity, operation, currentSourceCaptures);
        }
        if (!currentSourceCaptures && operation == null) {
            return null;
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
    private void luma$recordMutation(
            ServerLevel serverLevel,
            PendingBlockMutation mutation,
            BlockState requestedState,
            BlockState appliedState,
            CompoundTag newBlockEntity
    ) {
        for (HistoryCaptureManager.BlockChangeInput input : LUMA_POST_CALLBACK_MUTATION_POLICY.changesAfterCallbacks(
                mutation.pos(),
                mutation.oldState(),
                requestedState,
                appliedState,
                mutation.oldBlockEntity(),
                newBlockEntity
        )) {
            this.luma$recordMutation(serverLevel, mutation.operation(), input);
        }
    }

    @Unique
    private void luma$recordMutation(
            ServerLevel serverLevel,
            ObservedExternalToolOperation operation,
            HistoryCaptureManager.BlockChangeInput input
    ) {
        if (operation == null) {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    serverLevel,
                    input.pos(),
                    input.oldState(),
                    input.newState(),
                    input.oldBlockEntity(),
                    input.newBlockEntity()
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
                    input.pos(),
                    input.oldState(),
                    input.newState(),
                    input.oldBlockEntity(),
                    input.newBlockEntity()
            );
        }
    }

    @Unique
    private CompoundTag luma$blockEntityTag(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (state == null || !state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        return BlockEntitySnapshot.capture(serverLevel, blockEntity);
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
