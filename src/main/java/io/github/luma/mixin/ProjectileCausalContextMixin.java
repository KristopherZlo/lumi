package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Projectile.class)
abstract class ProjectileCausalContextMixin {

    @Unique
    private static final EntityCausalContextRegistry LUMA_ENTITY_CAUSAL_CONTEXTS =
            EntityCausalContextRegistry.getInstance();

    @WrapMethod(method = "onHit")
    private void luma$wrapProjectileHit(HitResult hitResult, Operation<Void> original) {
        Projectile projectile = (Projectile) (Object) this;
        if (!(projectile.level() instanceof ServerLevel level)) {
            original.call(hitResult);
            return;
        }

        try (EntityCausalContextRegistry.ContextFrame ignored =
                     LUMA_ENTITY_CAUSAL_CONTEXTS.pushIfPresent(projectile, level)) {
            original.call(hitResult);
        }
    }
}
