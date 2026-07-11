package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.BlockUpdateCaptureContext;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldReplayTickSuppression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockUpdateCaptureMixin {

    @Unique
    private static final BlockUpdateCaptureContext LUMA_BLOCK_UPDATE_CONTEXT =
            BlockUpdateCaptureContext.getInstance();
    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();

    @WrapMethod(method = "handleNeighborChanged")
    private void luma$wrapNeighborChanged(
            Level level,
            BlockPos pos,
            Block block,
            Orientation orientation,
            boolean movedByPiston,
            Operation<Void> original
    ) {
        if (level instanceof ServerLevel serverLevel
                && LUMA_REPLAY_TICK_SUPPRESSION.shouldSuppressCallback(serverLevel, pos)) {
            return;
        }
        WorldMutationContext.SourceFrame sourceFrame = LUMA_BLOCK_UPDATE_CONTEXT.pushFor(this.luma$state());
        try {
            original.call(level, pos, block, orientation, movedByPiston);
        } finally {
            this.luma$close(sourceFrame);
        }
    }

    @WrapMethod(method = "tick")
    private void luma$wrapScheduledTick(
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Operation<Void> original
    ) {
        if (LUMA_REPLAY_TICK_SUPPRESSION.shouldSuppressCallback(level, pos)) {
            return;
        }
        WorldMutationContext.SourceFrame sourceFrame = LUMA_BLOCK_UPDATE_CONTEXT.pushFor(this.luma$state());
        try {
            original.call(level, pos, random);
        } finally {
            this.luma$close(sourceFrame);
        }
    }

    @Unique
    private BlockState luma$state() {
        return (BlockState) (Object) this;
    }

    @Unique
    private void luma$close(WorldMutationContext.SourceFrame sourceFrame) {
        if (sourceFrame != null) {
            sourceFrame.close();
        }
    }
}
