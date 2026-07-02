package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.ExplosiveEntityContextRegistry;
import io.github.luma.minecraft.capture.WorldMutationContext;
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

@Mixin(ServerLevel.class)
abstract class ServerLevelExplosionMixin {

    @Unique
    private static final ExplosiveEntityContextRegistry LUMA_EXPLOSIVE_CONTEXTS =
            ExplosiveEntityContextRegistry.getInstance();

    @Unique
    private static final EntityCausalContextRegistry LUMA_ENTITY_CAUSAL_CONTEXTS =
            EntityCausalContextRegistry.getInstance();

    @WrapMethod(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/util/random/WeightedList;Lnet/minecraft/core/Holder;)V")
    private void luma$wrapServerExplosion(
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
            Operation<Void> original
    ) {
        EntityCausalContextRegistry.ContextFrame entityFrame =
                LUMA_ENTITY_CAUSAL_CONTEXTS.pushIfPresent(
                        entity,
                        (ServerLevel) (Object) this,
                        WorldMutationSource.EXPLOSION
                );
        boolean entityContextual = entityFrame.active();
        boolean explosiveContextual = !entityContextual && LUMA_EXPLOSIVE_CONTEXTS.pushContext(entity);
        WorldMutationContext.SourceFrame fallbackFrame = null;
        if (!entityContextual && !explosiveContextual) {
            fallbackFrame = WorldMutationContext.pushSource(WorldMutationSource.EXPLOSION);
        }

        try {
            original.call(
                    entity,
                    damageSource,
                    calculator,
                    x,
                    y,
                    z,
                    power,
                    createFire,
                    interaction,
                    smallParticle,
                    largeParticle,
                    explosionParticles,
                    sound
            );
        } finally {
            if (entityContextual) {
                entityFrame.close();
            } else if (explosiveContextual) {
                try {
                    LUMA_EXPLOSIVE_CONTEXTS.forget(entity);
                } finally {
                    WorldMutationContext.popSource();
                }
            } else if (fallbackFrame != null) {
                fallbackFrame.close();
            }
        }
    }
}
