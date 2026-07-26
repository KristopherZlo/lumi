package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.MinecraftLiveEntityTracker;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @WrapMethod(method = "hurtServer")
    private boolean lumi$trackDamage(
            ServerLevel level, DamageSource source, float amount,
            Operation<Boolean> original) {
        var runtime = LumiMod.serverRuntime().find(level).orElse(null);
        if (runtime == null) {
            return original.call(level, source, amount);
        }
        LivingEntity entity = (LivingEntity) (Object) this;
        Optional<MinecraftLiveEntityTracker.Pending> pending = Optional.empty();
        try {
            pending = runtime.liveEntities().begin(entity);
        } catch (IOException failed) {
            LumiMod.LOGGER.warn(
                    "Cannot capture live entity {} before damage",
                    entity.getUUID(), failed);
        }
        try {
            return original.call(level, source, amount);
        } finally {
            try {
                if (pending.isPresent()) {
                    runtime.liveEntities().finish(pending.orElseThrow());
                }
            } catch (IOException failed) {
                LumiMod.LOGGER.warn(
                        "Cannot capture live entity {} after damage",
                        entity.getUUID(), failed);
            }
        }
    }
}
