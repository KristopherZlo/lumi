package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.MinecraftLiveEntityTracker;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Captures durable entities before explosion damage can destroy their state. */
@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {
    @WrapOperation(
            method = "hurtEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;hurtServer("
                            + "Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean lumi$captureDamagedEntity(
            Entity entity,
            ServerLevel level,
            DamageSource source,
            float amount,
            Operation<Boolean> original) {
        var runtime = LumiMod.serverRuntime().find(level).orElse(null);
        Optional<MinecraftLiveEntityTracker.Pending> pending = Optional.empty();
        if (runtime != null) {
            try {
                pending = runtime.liveEntities().begin(entity);
            } catch (IOException failed) {
                LumiMod.LOGGER.warn("Cannot capture entity {} before explosion damage",
                        entity.getUUID(), failed);
            }
        }
        try {
            return original.call(entity, level, source, amount);
        } finally {
            if (runtime != null && pending.isPresent()) {
                try {
                    runtime.liveEntities().finish(pending.orElseThrow());
                } catch (IOException failed) {
                    LumiMod.LOGGER.warn("Cannot finish explosion-damaged entity {}",
                            entity.getUUID(), failed);
                }
            }
        }
    }
}
