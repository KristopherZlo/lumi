package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SaplingBlock.class)
abstract class SaplingBlockMixin {

    @WrapMethod(method = "randomTick")
    private void luma$wrapRandomGrowth(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Operation<Void> original
    ) {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.GROWTH)) {
            original.call(state, level, pos, random);
        }
    }

    @WrapMethod(method = "performBonemeal")
    private void luma$wrapBonemealGrowth(
            ServerLevel level,
            RandomSource random,
            BlockPos pos,
            BlockState state,
            Operation<Void> original
    ) {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(WorldMutationSource.GROWTH)) {
            original.call(level, random, pos, state);
        }
    }
}
