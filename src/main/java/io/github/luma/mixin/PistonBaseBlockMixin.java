package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
abstract class PistonBaseBlockMixin {

    @Unique
    private int luma$pistonDepth = 0;

    @Inject(method = "triggerEvent", at = @At("HEAD"))
    private void luma$beginPistonEvent(BlockState state, Level level, BlockPos pos, int type, int data, CallbackInfoReturnable<Boolean> cir) {
        if (level.isClientSide()) {
            return;
        }

        this.luma$pistonDepth += 1;
        WorldMutationContext.pushSource(WorldMutationSource.PISTON);
    }

    @Inject(method = "triggerEvent", at = @At("RETURN"))
    private void luma$endPistonEvent(BlockState state, Level level, BlockPos pos, int type, int data, CallbackInfoReturnable<Boolean> cir) {
        if (this.luma$pistonDepth <= 0) {
            return;
        }

        this.luma$pistonDepth -= 1;
        WorldMutationContext.popSource();
    }
}
