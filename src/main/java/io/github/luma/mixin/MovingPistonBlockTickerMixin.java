package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.DeferredActionFalloutGuard;
import io.github.luma.minecraft.capture.DeferredWorldMutationContexts;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import io.github.luma.minecraft.world.WorldReplayTickSuppression;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MovingPistonBlock.class)
abstract class MovingPistonBlockTickerMixin {

    @Unique
    private static final DeferredActionFalloutGuard LUMA_DEFERRED_ACTION_FALLOUT_GUARD =
            DeferredActionFalloutGuard.getInstance();
    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();
    @Unique
    private static final HistoryDebugLog LUMA_HISTORY_DEBUG_LOG = new HistoryDebugLog();

    @WrapMethod(method = "getTicker")
    private BlockEntityTicker<PistonMovingBlockEntity> luma$wrapMovingPistonTicker(
            Level level,
            BlockState state,
            BlockEntityType<PistonMovingBlockEntity> type,
            Operation<BlockEntityTicker<PistonMovingBlockEntity>> original
    ) {
        BlockEntityTicker<PistonMovingBlockEntity> ticker = original.call(level, state, type);
        if (ticker == null) {
            return null;
        }
        return (tickerLevel, pos, tickerState, blockEntity) -> {
            if (tickerLevel.isClientSide()) {
                ticker.tick(tickerLevel, pos, tickerState, blockEntity);
                return;
            }
            DeferredWorldMutationContexts.push(blockEntity);
            try {
                if (tickerLevel instanceof ServerLevel serverLevel
                        && LUMA_REPLAY_TICK_SUPPRESSION.shouldSuppressCallback(serverLevel, pos)) {
                    LUMA_HISTORY_DEBUG_LOG.logSuppressedCallback(
                            "moving-piston-ticker",
                            serverLevel,
                            pos,
                            tickerState,
                            blockEntity == null ? "" : "blockEntity=" + blockEntity.getClass().getSimpleName()
                    );
                    return;
                }
                if (tickerLevel instanceof ServerLevel serverLevel
                        && LUMA_DEFERRED_ACTION_FALLOUT_GUARD.shouldSuppressCurrent(serverLevel)) {
                    LUMA_HISTORY_DEBUG_LOG.logSuppressedCallback(
                            "moving-piston-ticker-action",
                            serverLevel,
                            pos,
                            tickerState,
                            blockEntity == null ? "" : "blockEntity=" + blockEntity.getClass().getSimpleName()
                    );
                    return;
                }
                ticker.tick(tickerLevel, pos, tickerState, blockEntity);
            } finally {
                DeferredWorldMutationContexts.pop();
            }
        };
    }
}
