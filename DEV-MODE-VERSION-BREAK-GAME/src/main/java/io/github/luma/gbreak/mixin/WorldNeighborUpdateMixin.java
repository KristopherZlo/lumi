package io.github.luma.gbreak.mixin;

import io.github.luma.gbreak.bug.GameBreakingBug;
import io.github.luma.gbreak.state.BugStateController;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
abstract class WorldNeighborUpdateMixin {

    @Inject(method = "updateNeighborsAlways(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/world/block/WireOrientation;)V", at = @At("HEAD"), cancellable = true)
    private void gbreak$cancelUpdateNeighborsAlways(CallbackInfo ci) {
        if (this.gbreak$shouldSuppressNeighborUpdates()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateNeighborsExcept(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/util/math/Direction;Lnet/minecraft/world/block/WireOrientation;)V", at = @At("HEAD"), cancellable = true)
    private void gbreak$cancelUpdateNeighborsExcept(CallbackInfo ci) {
        if (this.gbreak$shouldSuppressNeighborUpdates()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateNeighbor(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/world/block/WireOrientation;)V", at = @At("HEAD"), cancellable = true)
    private void gbreak$cancelUpdateNeighbor(CallbackInfo ci) {
        if (this.gbreak$shouldSuppressNeighborUpdates()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateNeighbor(Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/world/block/WireOrientation;Z)V", at = @At("HEAD"), cancellable = true)
    private void gbreak$cancelStateAwareUpdateNeighbor(CallbackInfo ci) {
        if (this.gbreak$shouldSuppressNeighborUpdates()) {
            ci.cancel();
        }
    }

    @Inject(method = "replaceWithStateForNeighborUpdate(Lnet/minecraft/util/math/Direction;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)V", at = @At("HEAD"), cancellable = true)
    private void gbreak$cancelReplaceWithStateForNeighborUpdate(CallbackInfo ci) {
        if (this.gbreak$shouldSuppressNeighborUpdates()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateComparators(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;)V", at = @At("HEAD"), cancellable = true)
    private void gbreak$cancelUpdateComparators(CallbackInfo ci) {
        if (this.gbreak$shouldSuppressNeighborUpdates()) {
            ci.cancel();
        }
    }

    private boolean gbreak$shouldSuppressNeighborUpdates() {
        World world = (World) (Object) this;
        return !world.isClient() && BugStateController.getInstance().isActive(GameBreakingBug.NO_BLOCK_UPDATES);
    }
}
