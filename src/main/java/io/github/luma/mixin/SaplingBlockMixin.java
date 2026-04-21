package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SaplingBlock.class)
abstract class SaplingBlockMixin {

    @Unique
    private int luma$growthDepth = 0;

    @Inject(method = "randomTick", at = @At("HEAD"))
    private void luma$beginRandomGrowth(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        this.luma$pushGrowthSource();
    }

    @Inject(method = "randomTick", at = @At("RETURN"))
    private void luma$endRandomGrowth(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        this.luma$popGrowthSource();
    }

    @Inject(method = "performBonemeal", at = @At("HEAD"))
    private void luma$beginBonemealGrowth(ServerLevel level, RandomSource random, BlockPos pos, BlockState state, CallbackInfo ci) {
        this.luma$pushGrowthSource();
    }

    @Inject(method = "performBonemeal", at = @At("RETURN"))
    private void luma$endBonemealGrowth(ServerLevel level, RandomSource random, BlockPos pos, BlockState state, CallbackInfo ci) {
        this.luma$popGrowthSource();
    }

    @Unique
    private void luma$pushGrowthSource() {
        this.luma$growthDepth += 1;
        WorldMutationContext.pushSource(WorldMutationSource.GROWTH);
    }

    @Unique
    private void luma$popGrowthSource() {
        if (this.luma$growthDepth <= 0) {
            return;
        }

        this.luma$growthDepth -= 1;
        WorldMutationContext.popSource();
    }
}
