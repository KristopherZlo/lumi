package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {

    @Unique
    private int luma$playerOperationDepth = 0;

    @Inject(method = "handleChatCommand", at = @At("HEAD"))
    private void luma$beginChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        this.luma$pushPlayerSource();
    }

    @Inject(method = "handleChatCommand", at = @At("RETURN"))
    private void luma$endChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        this.luma$popPlayerSource();
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"))
    private void luma$beginSignedChatCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        this.luma$pushPlayerSource();
    }

    @Inject(method = "handleSignedChatCommand", at = @At("RETURN"))
    private void luma$endSignedChatCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        this.luma$popPlayerSource();
    }

    @Unique
    private void luma$pushPlayerSource() {
        this.luma$playerOperationDepth += 1;
        WorldMutationContext.pushSource(WorldMutationSource.PLAYER);
    }

    @Unique
    private void luma$popPlayerSource() {
        if (this.luma$playerOperationDepth <= 0) {
            return;
        }

        this.luma$playerOperationDepth -= 1;
        WorldMutationContext.popSource();
    }
}
