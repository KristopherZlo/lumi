package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.MinecraftLiveEntityTracker;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "handleInteract")
    private void lumi$trackEntityInteraction(
            ServerboundInteractPacket packet, Operation<Void> original) {
        var runtime = LumiMod.serverRuntime().find(player.level()).orElse(null);
        if (runtime == null) {
            original.call(packet);
            return;
        }
        if (!runtime.freeze().isMutationAllowed()) {
            return;
        }
        try (var ignored = DirectLiveActionContext.open(
                runtime.liveActions(), player.getUUID())) {
            Optional<MinecraftLiveEntityTracker.Pending> pending = lumi$beginCapture(
                    runtime.liveEntities(), packet.getTarget(player.level()));
            try {
                original.call(packet);
            } finally {
                lumi$finishCapture(runtime.liveEntities(), pending);
            }
        }
    }

    @Unique
    private static Optional<MinecraftLiveEntityTracker.Pending> lumi$beginCapture(
            MinecraftLiveEntityTracker tracker, Entity target) {
        if (target == null) {
            return Optional.empty();
        }
        try {
            return tracker.begin(target);
        } catch (IOException failed) {
            LumiMod.LOGGER.warn(
                    "Cannot capture interacted live entity {}", target.getUUID(), failed);
            return Optional.empty();
        }
    }

    @Unique
    private static void lumi$finishCapture(
            MinecraftLiveEntityTracker tracker,
            Optional<MinecraftLiveEntityTracker.Pending> pending) {
        try {
            if (pending.isPresent()) {
                tracker.finish(pending.orElseThrow());
            }
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Cannot finish interacted live entity", failed);
        }
    }
}
