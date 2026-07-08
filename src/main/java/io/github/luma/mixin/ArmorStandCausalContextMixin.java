package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ArmorStand.class)
abstract class ArmorStandCausalContextMixin {

    @Unique
    private static final EntityCausalContextRegistry LUMA_ENTITY_CAUSAL_CONTEXTS =
            EntityCausalContextRegistry.getInstance();

    @WrapMethod(method = "hurtServer")
    private boolean luma$wrapHurtServer(
            ServerLevel serverLevel,
            DamageSource damageSource,
            float amount,
            Operation<Boolean> original
    ) {
        LivingEntity entity = (LivingEntity) (Object) this;
        boolean hadCausalContext = LUMA_ENTITY_CAUSAL_CONTEXTS.hasContext(entity, serverLevel);
        boolean remembered = this.luma$rememberDamageContext(entity, serverLevel, damageSource);
        try {
            boolean damaged = original.call(serverLevel, damageSource, amount);
            if (remembered && entity.isDeadOrDying() && !entity.isRemoved()) {
                EntityMutationTracker.captureCausalDeath(entity);
            }
            return damaged;
        } finally {
            if (remembered && !hadCausalContext && !entity.isDeadOrDying() && !entity.isRemoved()) {
                LUMA_ENTITY_CAUSAL_CONTEXTS.clear(entity);
            }
        }
    }

    @Unique
    private boolean luma$rememberDamageContext(LivingEntity entity, ServerLevel level, DamageSource damageSource) {
        if (LUMA_ENTITY_CAUSAL_CONTEXTS.rememberCurrentPlayerAction(entity, level)) {
            return true;
        }
        Entity attacker = damageSource == null ? null : damageSource.getEntity();
        if (!(attacker instanceof ServerPlayer player)) {
            return false;
        }
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushPlayerSource(
                WorldMutationSource.PLAYER,
                player.getName().getString(),
                LumaAccessControl.getInstance().canUse(player) || WorldMutationContext.currentAccessAllowed()
        )) {
            return LUMA_ENTITY_CAUSAL_CONTEXTS.rememberCurrentPlayerAction(entity, level);
        }
    }
}
