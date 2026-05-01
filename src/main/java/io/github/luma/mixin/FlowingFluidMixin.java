package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {

    @WrapMethod(method = "tick")
    private void luma$wrapFluidTick(
            ServerLevel level,
            BlockPos pos,
            BlockState blockState,
            FluidState fluidState,
            Operation<Void> original
    ) {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.FLUID)) {
            original.call(level, pos, blockState, fluidState);
        }
    }
}
