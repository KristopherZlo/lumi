package io.github.luma.mixin;

import io.github.luma.minecraft.capture.EntityMutationTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
abstract class ServerLevelEntityLifecycleMixin {

    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void luma$captureAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            EntityMutationTracker.captureSpawn((ServerLevel) (Object) this, entity);
        }
    }

    @Inject(method = "addWithUUID", at = @At("RETURN"))
    private void luma$captureAddWithUuid(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            EntityMutationTracker.captureSpawn((ServerLevel) (Object) this, entity);
        }
    }

    @Inject(method = "addDuringTeleport", at = @At("RETURN"))
    private void luma$captureAddDuringTeleport(Entity entity, CallbackInfo ci) {
        EntityMutationTracker.captureSpawn((ServerLevel) (Object) this, entity);
    }
}
