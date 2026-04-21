package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {

    @Unique
    private int luma$fluidDepth = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void luma$beginFluidTick(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
        this.luma$fluidDepth += 1;
        WorldMutationContext.pushSource(WorldMutationSource.FLUID);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void luma$endFluidTick(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
        if (this.luma$fluidDepth <= 0) {
            return;
        }

        this.luma$fluidDepth -= 1;
        WorldMutationContext.popSource();
    }
}
