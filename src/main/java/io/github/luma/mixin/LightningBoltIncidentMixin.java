package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.DeferredWorldMutationContext;
import io.github.luma.minecraft.capture.DeferredWorldMutationContextAccess;
import io.github.luma.minecraft.capture.DeferredWorldMutationContexts;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps every tick of one lightning strike in one world-owned action. */
@Mixin(LightningBolt.class)
abstract class LightningBoltIncidentMixin implements DeferredWorldMutationContextAccess {

    @Unique
    private DeferredWorldMutationContext luma$deferredMutationContext;

    @WrapMethod(method = "tick")
    private void luma$wrapLightningIncident(Operation<Void> original) {
        if (DeferredWorldMutationContexts.pushSource(this)) {
            try {
                original.call();
            } finally {
                WorldMutationContext.popSource();
            }
            return;
        }
        LightningBolt lightning = (LightningBolt) (Object) this;
        if (!(lightning.level() instanceof ServerLevel level)) {
            original.call();
            return;
        }
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushWorldIncident(
                WorldMutationSource.MOB,
                "lightning",
                !level.getServer().isDedicatedServer()
        )) {
            DeferredWorldMutationContexts.remember(this, WorldMutationSource.MOB);
            original.call();
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
