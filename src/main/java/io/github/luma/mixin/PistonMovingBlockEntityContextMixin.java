package io.github.luma.mixin;

import io.github.luma.minecraft.capture.DeferredWorldMutationContext;
import io.github.luma.minecraft.capture.DeferredWorldMutationContextAccess;
import io.github.luma.minecraft.capture.DeferredWorldMutationContexts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMovingBlockEntityContextMixin implements DeferredWorldMutationContextAccess {

    @Unique
    private DeferredWorldMutationContext luma$deferredMutationContext;

    @Inject(method = "<init>(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;ZZ)V", at = @At("RETURN"))
    private void luma$rememberPistonContext(
            BlockPos pos,
            BlockState movedState,
            BlockState movingState,
            Direction direction,
            boolean extending,
            boolean sourcePiston,
            CallbackInfo ci
    ) {
        DeferredWorldMutationContexts.rememberPistonMovement(this);
    }

    @Override
    public DeferredWorldMutationContext luma$deferredMutationContext() {
        return this.luma$deferredMutationContext;
    }

    @Override
    public void luma$setDeferredMutationContext(DeferredWorldMutationContext context) {
        this.luma$deferredMutationContext = context;
    }
}
