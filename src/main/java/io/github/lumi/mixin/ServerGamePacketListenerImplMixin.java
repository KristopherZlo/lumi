package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.MinecraftLiveEntityTracker;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;
    @Unique private final Deque<Optional<InteractionCapture>> lumi$interactions =
            new ArrayDeque<>();

    @Inject(method = "handleInteract", at = @At("HEAD"))
    private void lumi$beginEntityInteraction(
            ServerboundInteractPacket packet, CallbackInfo callback) {
        var runtime = LumiMod.serverRuntime().find(player.level()).orElse(null);
        if (runtime == null) {
            lumi$interactions.addLast(Optional.empty());
            return;
        }
        DirectLiveActionContext.Scope scope = DirectLiveActionContext.open(
                runtime.liveActions(), player.getUUID());
        Optional<MinecraftLiveEntityTracker.Pending> pending = Optional.empty();
        Entity target = packet.getTarget(player.level());
        if (target != null) {
            try {
                pending = runtime.liveEntities().begin(target);
            } catch (IOException failed) {
                LumiMod.LOGGER.warn("Cannot capture interacted live entity {}",
                        target.getUUID(), failed);
            }
        }
        lumi$interactions.addLast(Optional.of(new InteractionCapture(
                scope, runtime.liveEntities(), pending)));
    }

    @Inject(method = "handleInteract", at = @At("RETURN"))
    private void lumi$finishEntityInteraction(
            ServerboundInteractPacket packet, CallbackInfo callback) {
        lumi$interactions.removeLast().ifPresent(interaction -> {
            try {
                if (interaction.pending().isPresent()) {
                    interaction.tracker().finish(interaction.pending().orElseThrow());
                }
            } catch (IOException failed) {
                LumiMod.LOGGER.warn("Cannot finish interacted live entity", failed);
            } finally {
                interaction.scope().close();
            }
        });
    }

    @Unique
    private record InteractionCapture(
            DirectLiveActionContext.Scope scope,
            MinecraftLiveEntityTracker tracker,
            Optional<MinecraftLiveEntityTracker.Pending> pending) { }
}
