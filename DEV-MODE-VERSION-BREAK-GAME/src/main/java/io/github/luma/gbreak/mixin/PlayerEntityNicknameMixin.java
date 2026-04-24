package io.github.luma.gbreak.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityNicknameMixin {

    private static final String DEMO_PLAYER_NAME = "ImZlo";

    @Inject(method = "getName", at = @At("HEAD"), cancellable = true)
    private void gbreak$getName(CallbackInfoReturnable<Text> callback) {
        callback.setReturnValue(Text.literal(DEMO_PLAYER_NAME));
    }

    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void gbreak$getDisplayName(CallbackInfoReturnable<Text> callback) {
        callback.setReturnValue(Text.literal(DEMO_PLAYER_NAME));
    }

    @Inject(method = "getStringifiedName", at = @At("HEAD"), cancellable = true)
    private void gbreak$getStringifiedName(CallbackInfoReturnable<String> callback) {
        callback.setReturnValue(DEMO_PLAYER_NAME);
    }

    @Inject(method = "getNameForScoreboard", at = @At("HEAD"), cancellable = true)
    private void gbreak$getNameForScoreboard(CallbackInfoReturnable<String> callback) {
        callback.setReturnValue(DEMO_PLAYER_NAME);
    }
}
