package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BoneMealItem.class)
abstract class BoneMealItemMixin {

    @WrapMethod(method = "growCrop")
    private static boolean luma$wrapGrowCrop(
            ItemStack stack,
            Level level,
            BlockPos pos,
            Operation<Boolean> original
    ) {
        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushCausalSource(WorldMutationSource.GROWTH)) {
            return original.call(stack, level, pos);
        }
    }

    @WrapMethod(method = "growWaterPlant")
    private static boolean luma$wrapGrowWaterPlant(
            ItemStack stack,
            Level level,
            BlockPos pos,
            Direction clickedSide,
            Operation<Boolean> original
    ) {
        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushCausalSource(WorldMutationSource.GROWTH)) {
            return original.call(stack, level, pos, clickedSide);
        }
    }
}
