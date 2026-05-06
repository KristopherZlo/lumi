package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.luma.minecraft.capture.DeferredWorldMutationContexts;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelTicks.class)
abstract class LevelTicksContextMixin {

    @Inject(method = "schedule", at = @At("HEAD"))
    private <T> void luma$rememberScheduledTickContext(ScheduledTick<T> scheduledTick, CallbackInfo ci) {
        DeferredWorldMutationContexts.remember(scheduledTick, null);
    }

    @WrapOperation(
            method = "runCollectedTicks",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"
            )
    )
    private <T> void luma$wrapScheduledTickContext(
            BiConsumer<BlockPos, T> consumer,
            Object pos,
            Object value,
            Operation<Void> original,
            @Local ScheduledTick<T> scheduledTick
    ) {
        DeferredWorldMutationContexts.push(scheduledTick);
        try {
            original.call(consumer, pos, value);
        } finally {
            DeferredWorldMutationContexts.pop();
        }
    }
}
