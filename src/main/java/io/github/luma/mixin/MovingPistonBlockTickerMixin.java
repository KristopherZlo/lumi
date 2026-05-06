package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.DeferredWorldMutationContexts;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MovingPistonBlock.class)
abstract class MovingPistonBlockTickerMixin {

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
                ticker.tick(tickerLevel, pos, tickerState, blockEntity);
            } finally {
                DeferredWorldMutationContexts.pop();
            }
        };
    }
}
