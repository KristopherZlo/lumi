package io.github.lumi.gametest.mixin;

import io.github.lumi.gametest.LumiInteractionTiming;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerUsePacketTimingMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void lumi$observeUsePacket(ServerboundUseItemOnPacket packet, CallbackInfo callback) {
        if (player.level().getServer().isSameThread()) LumiInteractionTiming.recordServerPacket();
    }
}
