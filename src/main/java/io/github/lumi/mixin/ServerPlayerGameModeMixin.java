package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
    @Shadow protected ServerLevel level;
    @Shadow @Final protected ServerPlayer player;
    @Unique private final Deque<Optional<DirectLiveActionContext.Scope>> lumi$scopes =
            new ArrayDeque<>();

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void lumi$beginDestroy(BlockPos position, CallbackInfoReturnable<Boolean> callback) {
        lumi$begin();
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void lumi$endDestroy(BlockPos position, CallbackInfoReturnable<Boolean> callback) {
        lumi$end();
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void lumi$beginUse(
            ServerPlayer actor, Level world, ItemStack stack, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> callback) {
        lumi$begin();
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void lumi$endUse(
            ServerPlayer actor, Level world, ItemStack stack, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> callback) {
        lumi$end();
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void lumi$beginUseOn(
            ServerPlayer actor, Level world, ItemStack stack, InteractionHand hand,
            BlockHitResult hit, CallbackInfoReturnable<InteractionResult> callback) {
        lumi$begin();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void lumi$endUseOn(
            ServerPlayer actor, Level world, ItemStack stack, InteractionHand hand,
            BlockHitResult hit, CallbackInfoReturnable<InteractionResult> callback) {
        lumi$end();
    }

    @Unique
    private void lumi$begin() {
        var runtime = LumiMod.serverRuntime().find(level);
        lumi$scopes.addLast(runtime.map(value ->
                DirectLiveActionContext.open(value.liveActions(), player.getUUID())));
    }

    @Unique
    private void lumi$end() {
        if (lumi$scopes.isEmpty()) {
            throw new IllegalStateException("Missing Lumi direct action scope");
        }
        lumi$scopes.removeLast().ifPresent(DirectLiveActionContext.Scope::close);
    }
}
