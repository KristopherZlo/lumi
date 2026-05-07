package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.DeferredActionFalloutGuard;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.debug.HistoryDebugLog;
import io.github.luma.minecraft.world.WorldReplayTickSuppression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PistonBaseBlock.class)
abstract class PistonBaseBlockMixin {

    @Unique
    private static final DeferredActionFalloutGuard LUMA_DEFERRED_ACTION_FALLOUT_GUARD =
            DeferredActionFalloutGuard.getInstance();
    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();
    @Unique
    private static final HistoryDebugLog LUMA_HISTORY_DEBUG_LOG = new HistoryDebugLog();

    @WrapMethod(method = "triggerEvent")
    private boolean luma$wrapPistonEvent(
            BlockState state,
            Level level,
            BlockPos pos,
            int type,
            int data,
            Operation<Boolean> original
    ) {
        if (level.isClientSide()) {
            return original.call(state, level, pos, type, data);
        }
        if (level instanceof ServerLevel serverLevel
                && LUMA_REPLAY_TICK_SUPPRESSION.shouldSuppressCallback(serverLevel, pos)) {
            LUMA_HISTORY_DEBUG_LOG.logSuppressedCallback(
                    "piston-trigger-event",
                    serverLevel,
                    pos,
                    state,
                    "type=" + type + " data=" + data
            );
            return false;
        }
        if (level instanceof ServerLevel serverLevel
                && LUMA_DEFERRED_ACTION_FALLOUT_GUARD.shouldSuppressCurrent(serverLevel)) {
            LUMA_HISTORY_DEBUG_LOG.logSuppressedCallback(
                    "piston-trigger-event-action",
                    serverLevel,
                    pos,
                    state,
                    "type=" + type + " data=" + data
            );
            return false;
        }
        if (WorldMutationContext.internalWorldApplyActive()) {
            if (level instanceof ServerLevel serverLevel) {
                LUMA_HISTORY_DEBUG_LOG.logSuppressedCallback(
                        "piston-trigger-event-internal-apply",
                        serverLevel,
                        pos,
                        state,
                        "type=" + type + " data=" + data
                );
            }
            return false;
        }

        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.PISTON)) {
            return original.call(state, level, pos, type, data);
        }
    }

    @WrapMethod(method = "checkIfExtend")
    private void luma$wrapPistonExtensionCheck(
            Level level,
            BlockPos pos,
            BlockState state,
            Operation<Void> original
    ) {
        if (level instanceof ServerLevel serverLevel
                && LUMA_REPLAY_TICK_SUPPRESSION.shouldSuppressCallback(serverLevel, pos)) {
            LUMA_HISTORY_DEBUG_LOG.logSuppressedCallback(
                    "piston-check-extend",
                    serverLevel,
                    pos,
                    state,
                    ""
            );
            return;
        }
        if (!level.isClientSide() && WorldMutationContext.internalWorldApplyActive()) {
            if (level instanceof ServerLevel serverLevel) {
                LUMA_HISTORY_DEBUG_LOG.logSuppressedCallback(
                        "piston-check-extend-internal-apply",
                        serverLevel,
                        pos,
                        state,
                        ""
                );
            }
            return;
        }

        original.call(level, pos, state);
    }
}
