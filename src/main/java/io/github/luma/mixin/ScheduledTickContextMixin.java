package io.github.luma.mixin;

import io.github.luma.minecraft.capture.DeferredWorldMutationContext;
import io.github.luma.minecraft.capture.DeferredWorldMutationContextAccess;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ScheduledTick.class)
abstract class ScheduledTickContextMixin implements DeferredWorldMutationContextAccess {

    @Unique
    private DeferredWorldMutationContext luma$deferredMutationContext;

    @Override
    public DeferredWorldMutationContext luma$deferredMutationContext() {
        return this.luma$deferredMutationContext;
    }

    @Override
    public void luma$setDeferredMutationContext(DeferredWorldMutationContext context) {
        this.luma$deferredMutationContext = context;
    }
}
