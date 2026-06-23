package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.debug.LumaDebugLog;
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
            luma$logBoneMeal("start", "growCrop", level, pos, null, null);
            try {
                boolean result = original.call(stack, level, pos);
                luma$logBoneMeal("finish", "growCrop", level, pos, result, null);
                return result;
            } catch (RuntimeException exception) {
                luma$logBoneMeal("failure", "growCrop", level, pos, null, exception);
                throw exception;
            }
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
            luma$logBoneMeal("start", "growWaterPlant", level, pos, null, null);
            try {
                boolean result = original.call(stack, level, pos, clickedSide);
                luma$logBoneMeal("finish", "growWaterPlant", level, pos, result, null);
                return result;
            } catch (RuntimeException exception) {
                luma$logBoneMeal("failure", "growWaterPlant", level, pos, null, exception);
                throw exception;
            }
        }
    }

    private static void luma$logBoneMeal(
            String phase,
            String method,
            Level level,
            BlockPos pos,
            Boolean result,
            RuntimeException exception
    ) {
        if (level == null || level.isClientSide() || !LumaDebugLog.globalEnabled()) {
            return;
        }
        LumaDebugLog.log(
                "capture",
                "Bonemeal {} method={} dimension={} pos={} result={} source={} action={} actor={} accessAllowed={} error={}",
                phase,
                method,
                level.dimension().identifier(),
                luma$format(pos),
                result == null ? "<pending>" : result,
                WorldMutationContext.currentSource(),
                luma$blank(WorldMutationContext.currentActionId()),
                WorldMutationContext.currentActor(),
                WorldMutationContext.currentAccessAllowed(),
                exception == null ? "<none>" : exception.getClass().getSimpleName() + ": " + exception.getMessage()
        );
    }

    private static String luma$format(BlockPos pos) {
        return pos == null ? "unknown" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String luma$blank(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
