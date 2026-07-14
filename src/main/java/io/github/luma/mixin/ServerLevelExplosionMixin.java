package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.DeferredWorldMutationContexts;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerLevel.class)
abstract class ServerLevelExplosionMixin {

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
        boolean explosiveContextual = !entityContextual && DeferredWorldMutationContexts.pushSource(entity);
        WorldMutationContext.SourceFrame fallbackFrame = null;
        if (!entityContextual && !explosiveContextual) {
            fallbackFrame = WorldMutationContext.hasCausalAction()
                    ? WorldMutationContext.pushSource(WorldMutationSource.EXPLOSION)
                    : entity instanceof Creeper
                    ? WorldMutationContext.pushWorldIncident(
                            WorldMutationSource.EXPLOSION,
                            "creeper",
                            !((ServerLevel) (Object) this).getServer().isDedicatedServer()
                    )
                    : WorldMutationContext.pushSource(WorldMutationSource.EXPLOSION);
        }
        String contextKind = entityContextual ? "entity-causal" : explosiveContextual ? "explosive" : "ambient";
        this.luma$logCreeperExplosion(entity, x, y, z, power, createFire, interaction, contextKind);
        this.luma$logPrimedTntExplosion(entity, x, y, z, power, createFire, interaction, contextKind);

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
                    DeferredWorldMutationContexts.clear(entity);
                } finally {
                    WorldMutationContext.popSource();
                }
            } else if (fallbackFrame != null) {
                fallbackFrame.close();
            }
        }
    }

    @Unique
    private void luma$logCreeperExplosion(
            Entity entity,
            double x,
            double y,
            double z,
            float power,
            boolean createFire,
            Level.ExplosionInteraction interaction,
            String contextKind
    ) {
        if (!(entity instanceof Creeper)) {
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        LumaLoadLog.event("creeper-explosion", "server-explode",
                "uuid=" + entity.getUUID()
                        + ", context=" + contextKind
                        + ", source=" + WorldMutationContext.currentSource()
                        + ", action=" + WorldMutationContext.currentActionId()
                        + ", actor=" + WorldMutationContext.currentActor()
                        + ", access=" + WorldMutationContext.currentAccessAllowed()
                        + ", time=" + level.getGameTime()
                        + ", pos=" + x + "," + y + "," + z
                        + ", power=" + power
                        + ", fire=" + createFire
                        + ", interaction=" + interaction);
    }

    @Unique
    private void luma$logPrimedTntExplosion(
            Entity entity,
            double x,
            double y,
            double z,
            float power,
            boolean createFire,
            Level.ExplosionInteraction interaction,
            String contextKind
    ) {
        if (!(entity instanceof PrimedTnt)) {
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        LumaLoadLog.event("tnt-replay", "server-explode",
                "uuid=" + entity.getUUID()
                        + ", context=" + contextKind
                        + ", source=" + WorldMutationContext.currentSource()
                        + ", action=" + WorldMutationContext.currentActionId()
                        + ", actor=" + WorldMutationContext.currentActor()
                        + ", access=" + WorldMutationContext.currentAccessAllowed()
                        + ", time=" + level.getGameTime()
                        + ", pos=" + x + "," + y + "," + z
                        + ", power=" + power
                        + ", fire=" + createFire
                        + ", interaction=" + interaction);
    }
}
