package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {

    @Unique
    private int luma$playerMutationDepth = 0;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void luma$beginDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        this.luma$pushPlayerSource();
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void luma$endDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        this.luma$popPlayerSource();
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void luma$beginUseItem(
            ServerPlayer player,
            Level level,
            ItemStack stack,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        this.luma$pushPlayerSource();
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void luma$endUseItem(
            ServerPlayer player,
            Level level,
            ItemStack stack,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        this.luma$popPlayerSource();
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void luma$beginUseItemOn(
            ServerPlayer player,
            Level level,
            ItemStack stack,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        this.luma$pushPlayerSource();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void luma$endUseItemOn(
            ServerPlayer player,
            Level level,
            ItemStack stack,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        this.luma$popPlayerSource();
    }

    @Unique
    private void luma$pushPlayerSource() {
        this.luma$playerMutationDepth += 1;
        WorldMutationContext.pushSource(WorldMutationSource.PLAYER);
    }

    @Unique
    private void luma$popPlayerSource() {
        if (this.luma$playerMutationDepth <= 0) {
            return;
        }

        this.luma$playerMutationDepth -= 1;
        WorldMutationContext.popSource();
    }
}
