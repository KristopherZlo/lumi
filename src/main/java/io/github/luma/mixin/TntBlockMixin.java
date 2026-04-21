package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TntBlock.class)
abstract class TntBlockMixin {

    @Unique
    private int luma$explosiveDepth = 0;

    @Inject(method = "onPlace", at = @At("HEAD"))
    private void luma$beginOnPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved, CallbackInfo ci) {
        this.luma$pushExplosiveSource(level);
    }

    @Inject(method = "onPlace", at = @At("RETURN"))
    private void luma$endOnPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved, CallbackInfo ci) {
        this.luma$popExplosiveSource();
    }

    @Inject(method = "neighborChanged", at = @At("HEAD"))
    private void luma$beginNeighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block sourceBlock,
            Orientation orientation,
            boolean movedByPiston,
            CallbackInfo ci
    ) {
        this.luma$pushExplosiveSource(level);
    }

    @Inject(method = "neighborChanged", at = @At("RETURN"))
    private void luma$endNeighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block sourceBlock,
            Orientation orientation,
            boolean movedByPiston,
            CallbackInfo ci
    ) {
        this.luma$popExplosiveSource();
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void luma$beginUseItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        this.luma$pushExplosiveSource(level);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void luma$endUseItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        this.luma$popExplosiveSource();
    }

    @Inject(method = "onProjectileHit", at = @At("HEAD"))
    private void luma$beginProjectileHit(Level level, BlockState state, BlockHitResult hitResult, Projectile projectile, CallbackInfo ci) {
        this.luma$pushExplosiveSource(level);
    }

    @Inject(method = "onProjectileHit", at = @At("RETURN"))
    private void luma$endProjectileHit(Level level, BlockState state, BlockHitResult hitResult, Projectile projectile, CallbackInfo ci) {
        this.luma$popExplosiveSource();
    }

    @Unique
    private void luma$pushExplosiveSource(Level level) {
        if (level.isClientSide()) {
            return;
        }

        this.luma$explosiveDepth += 1;
        WorldMutationContext.pushSource(WorldMutationSource.EXPLOSIVE);
    }

    @Unique
    private void luma$popExplosiveSource() {
        if (this.luma$explosiveDepth <= 0) {
            return;
        }

        this.luma$explosiveDepth -= 1;
        WorldMutationContext.popSource();
    }
}
