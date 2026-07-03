package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.minecraft.capture.EntityConstructionStateAccess;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.EntityMutationTracker.PendingEntityMutation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class EntityMutationMixin implements EntityConstructionStateAccess {

    @Unique
    private static final EntityCausalContextRegistry LUMA_ENTITY_CAUSAL_CONTEXTS =
            EntityCausalContextRegistry.getInstance();

    @Unique
    private boolean luma$baseEntityConstructed;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void luma$markBaseEntityConstructed(EntityType<?> entityType, Level level, CallbackInfo ci) {
        this.luma$baseEntityConstructed = true;
    }

    @Override
    public boolean luma$baseEntityConstructed() {
        return this.luma$baseEntityConstructed;
    }

    @WrapMethod(method = "setPos(DDD)V")
    private void luma$wrapSetPos(double x, double y, double z, Operation<Void> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        original.call(x, y, z);
        this.luma$captureAfter(pending);
    }

    @WrapMethod(method = "snapTo(DDDFF)V")
    private void luma$wrapSnapTo(double x, double y, double z, float yRot, float xRot, Operation<Void> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        original.call(x, y, z, yRot, xRot);
        this.luma$captureAfter(pending);
    }

    @WrapMethod(method = "absSnapTo(DDDFF)V")
    private void luma$wrapAbsSnapTo(double x, double y, double z, float yRot, float xRot, Operation<Void> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        original.call(x, y, z, yRot, xRot);
        this.luma$captureAfter(pending);
    }

    @WrapMethod(method = "setYRot(F)V")
    private void luma$wrapSetYRot(float yRot, Operation<Void> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        original.call(yRot);
        this.luma$captureAfter(pending);
    }

    @WrapMethod(method = "setXRot(F)V")
    private void luma$wrapSetXRot(float xRot, Operation<Void> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        original.call(xRot);
        this.luma$captureAfter(pending);
    }

    @WrapMethod(method = "setCustomName")
    private void luma$wrapSetCustomName(Component name, Operation<Void> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        original.call(name);
        this.luma$captureAfter(pending);
    }

    @WrapMethod(method = "setInvisible")
    private void luma$wrapSetInvisible(boolean invisible, Operation<Void> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        original.call(invisible);
        this.luma$captureAfter(pending);
    }

    @WrapMethod(method = "setGlowingTag")
    private void luma$wrapSetGlowingTag(boolean glowing, Operation<Void> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        original.call(glowing);
        this.luma$captureAfter(pending);
    }

    @WrapMethod(method = "addTag")
    private boolean luma$wrapAddTag(String tag, Operation<Boolean> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        boolean changed = original.call(tag);
        if (changed) {
            this.luma$captureAfter(pending);
        }
        return changed;
    }

    @WrapMethod(method = "removeTag")
    private boolean luma$wrapRemoveTag(String tag, Operation<Boolean> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        boolean changed = original.call(tag);
        if (changed) {
            this.luma$captureAfter(pending);
        }
        return changed;
    }

    @WrapMethod(method = "load")
    private void luma$wrapLoad(ValueInput input, Operation<Void> original) {
        PendingEntityMutation pending = this.luma$captureBefore();
        original.call(input);
        this.luma$captureAfter(pending);
    }

    @WrapMethod(method = "remove")
    private void luma$wrapRemove(RemovalReason reason, Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        if (!(entity.level() instanceof ServerLevel level)) {
            original.call(reason);
            return;
        }
        try (EntityCausalContextRegistry.ContextFrame ignored =
                     LUMA_ENTITY_CAUSAL_CONTEXTS.pushIfPresent(entity, level)) {
            PendingEntityMutation pending = EntityMutationTracker.captureRemoval(entity);
            original.call(reason);
            this.luma$captureAfter(pending);
        } finally {
            if (reason != null && reason.shouldDestroy()) {
                LUMA_ENTITY_CAUSAL_CONTEXTS.clear(entity);
            }
        }
    }

    @Unique
    private PendingEntityMutation luma$captureBefore() {
        if (!this.luma$baseEntityConstructed) {
            return PendingEntityMutation.empty();
        }
        return EntityMutationTracker.captureBefore((Entity) (Object) this);
    }

    @Unique
    private void luma$captureAfter(PendingEntityMutation pending) {
        EntityMutationTracker.captureAfter((Entity) (Object) this, pending);
    }
}
