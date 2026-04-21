package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
abstract class FireBlockMixin {

    @Unique
    private int luma$fireDepth = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void luma$beginFireTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        this.luma$fireDepth += 1;
        WorldMutationContext.pushSource(WorldMutationSource.FIRE);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void luma$endFireTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (this.luma$fireDepth <= 0) {
            return;
        }

        this.luma$fireDepth -= 1;
        WorldMutationContext.popSource();
    }
}
