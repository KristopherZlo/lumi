package io.github.luma.mixin;

import io.github.luma.integration.common.ExternalToolMutationSourceResolver;
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
    private static final ExternalToolMutationSourceResolver LUMA_TOOL_SOURCE_RESOLVER =
            ExternalToolMutationSourceResolver.getInstance();

    @Unique
    private static final ThreadLocal<Deque<PendingBlockMutation>> LUMA_PENDING_MUTATIONS = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void luma$captureBeforeSetBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
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
        if (!currentSourceCaptures && operation == null) {
            return;
        }

        BlockState oldState = serverLevel.getBlockState(pos);
        CompoundTag oldBlockEntity = this.luma$blockEntityTag(serverLevel, pos, oldState);
        WorldMutationCaptureGuard.CaptureBoundary boundary = WorldMutationCaptureGuard.pushLevelSetBlockBoundary();
        LUMA_PENDING_MUTATIONS.get().push(new PendingBlockMutation(
                pos.immutable(),
                oldState,
                oldBlockEntity,
                operation,
                boundary
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

            BlockState appliedState = serverLevel.getBlockState(mutation.pos());
            CompoundTag newBlockEntity = this.luma$blockEntityTag(serverLevel, mutation.pos(), appliedState);
            this.luma$recordMutation(serverLevel, mutation, appliedState, newBlockEntity);
        } finally {
            mutation.boundary().close();
        }
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
