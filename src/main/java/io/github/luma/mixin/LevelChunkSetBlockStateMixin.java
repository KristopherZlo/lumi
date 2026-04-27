package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.integration.axiom.AxiomMutationOriginDetector;
import io.github.luma.integration.axiom.ObservedAxiomOperation;
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
    private static final AxiomMutationOriginDetector LUMA_AXIOM_DETECTOR = AxiomMutationOriginDetector.getInstance();

    @Unique
    private static final PendingAxiomBlockMutation LUMA_SKIPPED_AXIOM_MUTATION =
            new PendingAxiomBlockMutation(null, null, null, null);

    @Unique
    private static final ThreadLocal<Deque<PendingAxiomBlockMutation>> LUMA_PENDING_AXIOM_MUTATIONS =
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
        if (!LUMA_AXIOM_DETECTOR.canDetectOperation()) {
            return;
        }

        PendingAxiomBlockMutation mutation = LUMA_SKIPPED_AXIOM_MUTATION;
        if (HistoryCaptureManager.shouldCaptureMutation(WorldMutationContext.currentSource())) {
            LUMA_PENDING_AXIOM_MUTATIONS.get().push(mutation);
            return;
        }

        var operation = LUMA_AXIOM_DETECTOR.detectOperation();
        if (operation.isPresent()) {
            LevelChunk chunk = (LevelChunk) (Object) this;
            BlockState oldState = chunk.getBlockState(pos);
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            CompoundTag oldBlockEntity = blockEntity == null ? null : blockEntity.saveWithFullMetadata(serverLevel.registryAccess());
            mutation = new PendingAxiomBlockMutation(
                    pos.immutable(),
                    oldState,
                    oldBlockEntity,
                    operation.get()
            );
        }
        LUMA_PENDING_AXIOM_MUTATIONS.get().push(mutation);
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
        if (!LUMA_AXIOM_DETECTOR.canDetectOperation()) {
            return;
        }

        Deque<PendingAxiomBlockMutation> mutations = LUMA_PENDING_AXIOM_MUTATIONS.get();
        if (mutations.isEmpty()) {
            return;
        }

        PendingAxiomBlockMutation mutation = mutations.pop();
        if (!mutation.shouldCapture() || cir.getReturnValue() == null) {
            return;
        }

        LevelChunk chunk = (LevelChunk) (Object) this;
        BlockEntity blockEntity = chunk.getBlockEntity(mutation.pos());
        CompoundTag newBlockEntity = blockEntity == null ? null : blockEntity.saveWithFullMetadata(serverLevel.registryAccess());
        WorldMutationContext.pushExternalSource(
                WorldMutationSource.AXIOM,
                mutation.operation().actor(),
                mutation.operation().actionId()
        );
        try {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    serverLevel,
                    mutation.pos(),
                    mutation.oldState(),
                    chunk.getBlockState(mutation.pos()),
                    mutation.oldBlockEntity(),
                    newBlockEntity
            );
        } finally {
            WorldMutationContext.popSource();
        }
    }

    @Unique
    private record PendingAxiomBlockMutation(
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            ObservedAxiomOperation operation
    ) {

        private boolean shouldCapture() {
            return this.operation != null;
        }
    }
}
