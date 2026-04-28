package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateRandomTickMixin {

    @Inject(method = "randomTick", at = @At("HEAD"))
    private void luma$beginRandomTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        WorldMutationContext.pushSource(WorldMutationSource.GROWTH);
    }

    @Inject(method = "randomTick", at = @At("RETURN"))
    private void luma$endRandomTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        WorldMutationContext.popSource();
    }
}
