package io.github.luma.mixin;

import io.github.luma.debug.LumaLoadLog;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.ExplosiveEntityContextRegistry;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldReplayTickSuppression;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
abstract class ServerLevelEntityLifecycleMixin {

    @Unique
    private static final ExplosiveEntityContextRegistry LUMA_EXPLOSIVE_CONTEXTS =
            ExplosiveEntityContextRegistry.getInstance();

    @Unique
    private static final EntityCausalContextRegistry LUMA_ENTITY_CAUSAL_CONTEXTS =
            EntityCausalContextRegistry.getInstance();
    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void luma$suppressInternalItemDrop(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (this.luma$shouldSuppressInternalItemDrop(entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void luma$captureAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            this.luma$rememberCausalEntityAction(entity);
            LUMA_EXPLOSIVE_CONTEXTS.rememberSpawn(entity, (ServerLevel) (Object) this);
            EntityMutationTracker.captureSpawn((ServerLevel) (Object) this, entity);
        }
        this.luma$logPrimedTntSpawn("addFreshEntity", entity, cir.getReturnValue());
    }

    @Inject(method = "addWithUUID", at = @At("HEAD"), cancellable = true)
    private void luma$suppressInternalUuidItemDrop(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (this.luma$shouldSuppressInternalItemDrop(entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "addWithUUID", at = @At("RETURN"))
    private void luma$captureAddWithUuid(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            this.luma$rememberCausalEntityAction(entity);
            LUMA_EXPLOSIVE_CONTEXTS.rememberSpawn(entity, (ServerLevel) (Object) this);
            EntityMutationTracker.captureSpawn((ServerLevel) (Object) this, entity);
        }
        this.luma$logPrimedTntSpawn("addWithUUID", entity, cir.getReturnValue());
    }

    @Inject(method = "addDuringTeleport", at = @At("HEAD"), cancellable = true)
    private void luma$suppressInternalTeleportedItemDrop(Entity entity, CallbackInfo ci) {
        if (this.luma$shouldSuppressInternalItemDrop(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "addDuringTeleport", at = @At("RETURN"))
    private void luma$captureAddDuringTeleport(Entity entity, CallbackInfo ci) {
        this.luma$rememberCausalEntityAction(entity);
        LUMA_EXPLOSIVE_CONTEXTS.rememberSpawn(entity, (ServerLevel) (Object) this);
        EntityMutationTracker.captureSpawn((ServerLevel) (Object) this, entity);
        this.luma$logPrimedTntSpawn("addDuringTeleport", entity, true);
    }

    @Unique
    private void luma$rememberCausalEntityAction(Entity entity) {
        if (entity instanceof Projectile || entity instanceof FallingBlockEntity) {
            ServerLevel level = (ServerLevel) (Object) this;
            LUMA_ENTITY_CAUSAL_CONTEXTS.rememberCurrentActionIfAbsent(entity, level);
        }
    }

    @Unique
    private void luma$logPrimedTntSpawn(String method, Entity entity, boolean accepted) {
        if (!(entity instanceof PrimedTnt)) {
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        LumaLoadLog.event("tnt-replay", "primed-tnt-spawn",
                "method=" + method
                        + ", accepted=" + accepted
                        + ", uuid=" + entity.getUUID()
                        + ", time=" + level.getGameTime()
                        + ", frozen=" + LUMA_REPLAY_TICK_SUPPRESSION.shouldFreezeWorldTick(level)
                        + ", internalApply=" + WorldMutationContext.internalWorldApplyActive()
                        + ", entityReplay=" + WorldMutationContext.historyEntityReplayActive()
                        + ", source=" + WorldMutationContext.currentSource()
                        + ", action=" + WorldMutationContext.currentActionId()
                        + ", actor=" + WorldMutationContext.currentActor()
                        + ", pos=" + entity.blockPosition().getX()
                        + "," + entity.blockPosition().getY()
                        + "," + entity.blockPosition().getZ());
    }

    @Unique
    private boolean luma$shouldSuppressInternalItemDrop(Entity entity) {
        return entity instanceof ItemEntity
                && WorldMutationContext.internalWorldApplyActive()
                && !WorldMutationContext.historyEntityReplayActive();
    }
}
