package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntity.class)
abstract class LivingEntityCausalContextMixin {

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
        boolean remembered = LUMA_ENTITY_CAUSAL_CONTEXTS.rememberCurrentPlayerAction(entity, serverLevel);
        try {
            return original.call(serverLevel, damageSource, amount);
        } finally {
            if (remembered && !entity.isDeadOrDying() && !entity.isRemoved()) {
                LUMA_ENTITY_CAUSAL_CONTEXTS.clear(entity);
            }
        }
    }

    @WrapMethod(method = "dropAllDeathLoot")
    private void luma$wrapDropAllDeathLoot(
            ServerLevel serverLevel,
            DamageSource damageSource,
            Operation<Void> original
    ) {
        try (EntityCausalContextRegistry.ContextFrame ignored =
                     LUMA_ENTITY_CAUSAL_CONTEXTS.pushIfPresent((LivingEntity) (Object) this, serverLevel)) {
            original.call(serverLevel, damageSource);
        }
    }
}
