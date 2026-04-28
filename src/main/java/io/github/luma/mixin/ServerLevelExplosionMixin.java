package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.ExplosiveEntityContextRegistry;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelExplosionMixin {

    @Unique
    private static final ExplosiveEntityContextRegistry LUMA_EXPLOSIVE_CONTEXTS =
            ExplosiveEntityContextRegistry.getInstance();

    @Unique
    private final Deque<Boolean> luma$contextualExplosionStack = new ArrayDeque<>();

    @Inject(
            method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/util/random/WeightedList;Lnet/minecraft/core/Holder;)V",
            at = @At("HEAD")
    )
    private void luma$beginServerExplosion(
            Entity entity,
            DamageSource damageSource,
            ExplosionDamageCalculator calculator,
            double x,
            double y,
            double z,
            float power,
            boolean createFire,
            Level.ExplosionInteraction interaction,
            ParticleOptions smallParticle,
            ParticleOptions largeParticle,
            WeightedList<ExplosionParticleInfo> explosionParticles,
            Holder<SoundEvent> sound,
            CallbackInfo ci
    ) {
        boolean contextual = LUMA_EXPLOSIVE_CONTEXTS.pushContext(entity);
        this.luma$contextualExplosionStack.push(contextual);
        if (!contextual) {
            WorldMutationContext.pushSource(WorldMutationSource.EXPLOSION);
        }
    }

    @Inject(
            method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/util/random/WeightedList;Lnet/minecraft/core/Holder;)V",
            at = @At("RETURN")
    )
    private void luma$endServerExplosion(
            Entity entity,
            DamageSource damageSource,
            ExplosionDamageCalculator calculator,
            double x,
            double y,
            double z,
            float power,
            boolean createFire,
            Level.ExplosionInteraction interaction,
            ParticleOptions smallParticle,
            ParticleOptions largeParticle,
            WeightedList<ExplosionParticleInfo> explosionParticles,
            Holder<SoundEvent> sound,
            CallbackInfo ci
    ) {
        if (this.luma$contextualExplosionStack.isEmpty()) {
            return;
        }

        boolean contextual = this.luma$contextualExplosionStack.pop();
        if (contextual) {
            LUMA_EXPLOSIVE_CONTEXTS.forget(entity);
        }
        WorldMutationContext.popSource();
    }
}
