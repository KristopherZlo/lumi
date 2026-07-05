package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.DeferredWorldMutationContext;
import io.github.luma.minecraft.capture.DeferredWorldMutationContextAccess;
import io.github.luma.minecraft.capture.DeferredWorldMutationContexts;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PrimedTnt.class)
abstract class PrimedTntCausalContextMixin implements DeferredWorldMutationContextAccess {

    @Unique
    private DeferredWorldMutationContext luma$deferredMutationContext;

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", at = @At("RETURN"))
    private void luma$rememberConstructedTntContext(EntityType<? extends PrimedTnt> type, Level level, CallbackInfo ci) {
        this.luma$rememberCurrentExplosiveContext(level);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V", at = @At("RETURN"))
    private void luma$rememberPrimedTntContext(
            Level level,
            double x,
            double y,
            double z,
            LivingEntity owner,
            CallbackInfo ci
    ) {
        this.luma$rememberCurrentExplosiveContext(level);
    }

    @Unique
    private void luma$rememberCurrentExplosiveContext(Level level) {
        if (level != null && !level.isClientSide()) {
            DeferredWorldMutationContexts.remember(this, WorldMutationSource.EXPLOSIVE);
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
