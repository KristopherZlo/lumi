package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PistonBaseBlock.class)
abstract class PistonBaseBlockMixin {

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
        if (WorldMutationContext.internalWorldApplyActive()) {
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
        if (!level.isClientSide() && WorldMutationContext.internalWorldApplyActive()) {
            return;
        }

        original.call(level, pos, state);
    }
}
