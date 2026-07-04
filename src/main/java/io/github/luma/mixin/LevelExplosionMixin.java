package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.ExplosiveEntityContextRegistry;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Level.class)
abstract class LevelExplosionMixin {

    @Unique
    private static final ExplosiveEntityContextRegistry LUMA_EXPLOSIVE_CONTEXTS =
            ExplosiveEntityContextRegistry.getInstance();

    @Unique
    private static final EntityCausalContextRegistry LUMA_ENTITY_CAUSAL_CONTEXTS =
            EntityCausalContextRegistry.getInstance();

    @WrapMethod(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)V")
    private void luma$wrapExplosion(
            Entity entity,
            DamageSource damageSource,
            ExplosionDamageCalculator calculator,
            double x,
            double y,
            double z,
            float power,
            boolean createFire,
            Level.ExplosionInteraction interaction,
            Operation<Void> original
    ) {
        EntityCausalContextRegistry.ContextFrame entityFrame = this.luma$pushEntityContext(entity);
        boolean entityContextual = entityFrame != null && entityFrame.active();
        boolean explosiveContextual = !entityContextual && LUMA_EXPLOSIVE_CONTEXTS.pushContext(entity);
        WorldMutationContext.SourceFrame fallbackFrame = null;
        if (!entityContextual && !explosiveContextual) {
            fallbackFrame = WorldMutationContext.pushSource(WorldMutationSource.EXPLOSION);
        }
        String contextKind = entityContextual ? "entity-causal" : explosiveContextual ? "explosive" : "ambient";
        this.luma$logCreeperExplosion(entity, x, y, z, power, createFire, interaction, contextKind);
        this.luma$logPrimedTntExplosion(entity, x, y, z, power, createFire, interaction, contextKind);

        try {
            original.call(entity, damageSource, calculator, x, y, z, power, createFire, interaction);
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

    @Unique
    private EntityCausalContextRegistry.ContextFrame luma$pushEntityContext(Entity entity) {
        if ((Object) this instanceof ServerLevel level) {
            return LUMA_ENTITY_CAUSAL_CONTEXTS.pushIfPresent(entity, level, WorldMutationSource.EXPLOSION);
        }
        return null;
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
        if (!(entity instanceof Creeper) || !((Object) this instanceof ServerLevel level)) {
            return;
        }
        LumaLoadLog.event("creeper-explosion", "level-explode",
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
        if (!(entity instanceof PrimedTnt) || !((Object) this instanceof ServerLevel level)) {
            return;
        }
        LumaLoadLog.event("tnt-replay", "level-explode",
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
