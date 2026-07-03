package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.EntityConstructionStateAccess;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.EntityMutationTracker.PendingEntityMutation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SynchedEntityData.class)
abstract class SynchedEntityDataMutationMixin {

    @Unique
    private static final EntityCausalContextRegistry LUMA_ENTITY_CAUSAL_CONTEXTS =
            EntityCausalContextRegistry.getInstance();

    @Shadow
    @Final
    private SyncedDataHolder entity;

    @WrapMethod(method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V")
    private <T> void luma$wrapSet(
            EntityDataAccessor<T> key,
            T value,
            boolean force,
            Operation<Void> original
    ) {
        Entity modifiedEntity = this.entity instanceof Entity entity ? entity : null;
        if (modifiedEntity == null
                || modifiedEntity instanceof EntityConstructionStateAccess access && !access.luma$baseEntityConstructed()) {
            original.call(key, value, force);
            return;
        }

        PendingEntityMutation pending = EntityMutationTracker.captureBefore(modifiedEntity);
        if (pending.isEmpty()) {
            this.luma$rememberActualEntityDataChange(modifiedEntity);
            pending = EntityMutationTracker.captureUndoOnlyBefore(modifiedEntity);
        }
        original.call(key, value, force);
        EntityMutationTracker.captureAfter(modifiedEntity, pending);
    }

    @Unique
    private void luma$rememberActualEntityDataChange(Entity entity) {
        if (entity.level() instanceof ServerLevel level) {
            LUMA_ENTITY_CAUSAL_CONTEXTS.rememberCurrentPlayerActionIfAbsent(entity, level);
        }
    }
}
