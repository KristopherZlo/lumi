package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.DeferredWorldMutationContext;
import io.github.luma.minecraft.capture.DeferredWorldMutationContextAccess;
import io.github.luma.minecraft.capture.DeferredWorldMutationContexts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerExplosion.class)
abstract class ServerExplosionContextMixin implements DeferredWorldMutationContextAccess {

    @Unique
    private DeferredWorldMutationContext luma$deferredMutationContext;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void luma$rememberExplosionContext(
            ServerLevel level,
            Entity source,
            DamageSource damageSource,
            ExplosionDamageCalculator damageCalculator,
            Vec3 center,
            float radius,
            boolean fire,
            Explosion.BlockInteraction blockInteraction,
            CallbackInfo ci
    ) {
        DeferredWorldMutationContexts.remember(this, WorldMutationSource.EXPLOSION);
    }

    @WrapMethod(method = "explode")
    private int luma$wrapExplosionContext(Operation<Integer> original) {
        DeferredWorldMutationContexts.push(this);
        try {
            return original.call();
        } finally {
            DeferredWorldMutationContexts.pop();
        }
    }

    @Override
    public DeferredWorldMutationContext luma$deferredMutationContext() {
        return this.luma$deferredMutationContext;
    }

    @Override
    public void luma$setDeferredMutationContext(DeferredWorldMutationContext context) {
        this.luma$deferredMutationContext = context;
    }
}
