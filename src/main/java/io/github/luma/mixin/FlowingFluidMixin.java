package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import io.github.luma.minecraft.world.WorldReplayTickSuppression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {

    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();
    @Unique
    private static final HistoryDebugLog LUMA_HISTORY_DEBUG_LOG = new HistoryDebugLog();

    @WrapMethod(method = "tick")
    private void luma$wrapFluidTick(
            ServerLevel level,
            BlockPos pos,
            BlockState blockState,
            FluidState fluidState,
            Operation<Void> original
    ) {
        if (LUMA_REPLAY_TICK_SUPPRESSION.shouldSuppressCallback(level, pos)) {
            LUMA_HISTORY_DEBUG_LOG.logFluidTick(
                    "fluid-tick-suppressed",
                    level,
                    pos,
                    blockState,
                    fluidState,
                    "replay-callback"
            );
            return;
        }
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.FLUID)) {
            LUMA_HISTORY_DEBUG_LOG.logFluidTick("fluid-tick", level, pos, blockState, fluidState, "run");
            original.call(level, pos, blockState, fluidState);
        }
    }
}
