package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FallingBlock.class)
abstract class FallingBlockMixin {

    @Unique
    private int luma$fallingBlockDepth = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void luma$beginFallingBlockTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        this.luma$fallingBlockDepth += 1;
        WorldMutationContext.pushSource(WorldMutationSource.FALLING_BLOCK);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void luma$endFallingBlockTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (this.luma$fallingBlockDepth <= 0) {
            return;
        }

        this.luma$fallingBlockDepth -= 1;
        WorldMutationContext.popSource();
    }
}
