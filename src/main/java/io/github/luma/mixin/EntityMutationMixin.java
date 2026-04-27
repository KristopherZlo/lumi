package io.github.luma.mixin;

import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.EntityMutationTracker.PendingEntityMutation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityMutationMixin {

    @Unique
    private final ThreadLocal<PendingEntityMutation> luma$pendingEntityMutation = new ThreadLocal<>();

    @Inject(method = "setPos(DDD)V", at = @At("HEAD"))
    private void luma$beforeSetPos(double x, double y, double z, CallbackInfo ci) {
        this.luma$captureBefore();
    }

    @Inject(method = "setPos(DDD)V", at = @At("RETURN"))
    private void luma$afterSetPos(double x, double y, double z, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Inject(method = "snapTo(DDDFF)V", at = @At("HEAD"))
    private void luma$beforeSnapTo(double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        this.luma$captureBefore();
    }

    @Inject(method = "snapTo(DDDFF)V", at = @At("RETURN"))
    private void luma$afterSnapTo(double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Inject(method = "absSnapTo(DDDFF)V", at = @At("HEAD"))
    private void luma$beforeAbsSnapTo(double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        this.luma$captureBefore();
    }

    @Inject(method = "absSnapTo(DDDFF)V", at = @At("RETURN"))
    private void luma$afterAbsSnapTo(double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Inject(method = "setYRot(F)V", at = @At("HEAD"))
    private void luma$beforeSetYRot(float yRot, CallbackInfo ci) {
        this.luma$captureBefore();
    }

    @Inject(method = "setYRot(F)V", at = @At("RETURN"))
    private void luma$afterSetYRot(float yRot, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Inject(method = "setXRot(F)V", at = @At("HEAD"))
    private void luma$beforeSetXRot(float xRot, CallbackInfo ci) {
        this.luma$captureBefore();
    }

    @Inject(method = "setXRot(F)V", at = @At("RETURN"))
    private void luma$afterSetXRot(float xRot, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Inject(method = "setCustomName", at = @At("HEAD"))
    private void luma$beforeSetCustomName(Component name, CallbackInfo ci) {
        this.luma$captureBefore();
    }

    @Inject(method = "setCustomName", at = @At("RETURN"))
    private void luma$afterSetCustomName(Component name, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Inject(method = "setInvisible", at = @At("HEAD"))
    private void luma$beforeSetInvisible(boolean invisible, CallbackInfo ci) {
        this.luma$captureBefore();
    }

    @Inject(method = "setInvisible", at = @At("RETURN"))
    private void luma$afterSetInvisible(boolean invisible, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Inject(method = "setGlowingTag", at = @At("HEAD"))
    private void luma$beforeSetGlowingTag(boolean glowing, CallbackInfo ci) {
        this.luma$captureBefore();
    }

    @Inject(method = "setGlowingTag", at = @At("RETURN"))
    private void luma$afterSetGlowingTag(boolean glowing, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Inject(method = "addTag", at = @At("HEAD"))
    private void luma$beforeAddTag(String tag, CallbackInfoReturnable<Boolean> cir) {
        this.luma$captureBefore();
    }

    @Inject(method = "addTag", at = @At("RETURN"))
    private void luma$afterAddTag(String tag, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            this.luma$captureAfter();
        } else {
            this.luma$pendingEntityMutation.remove();
        }
    }

    @Inject(method = "removeTag", at = @At("HEAD"))
    private void luma$beforeRemoveTag(String tag, CallbackInfoReturnable<Boolean> cir) {
        this.luma$captureBefore();
    }

    @Inject(method = "removeTag", at = @At("RETURN"))
    private void luma$afterRemoveTag(String tag, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            this.luma$captureAfter();
        } else {
            this.luma$pendingEntityMutation.remove();
        }
    }

    @Inject(method = "load", at = @At("HEAD"))
    private void luma$beforeLoad(ValueInput input, CallbackInfo ci) {
        this.luma$captureBefore();
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void luma$afterLoad(ValueInput input, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void luma$beforeRemove(RemovalReason reason, CallbackInfo ci) {
        this.luma$pendingEntityMutation.set(EntityMutationTracker.captureRemoval((Entity) (Object) this));
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void luma$afterRemove(RemovalReason reason, CallbackInfo ci) {
        this.luma$captureAfter();
    }

    @Unique
    private void luma$captureBefore() {
        this.luma$pendingEntityMutation.set(EntityMutationTracker.captureBefore((Entity) (Object) this));
    }

    @Unique
    private void luma$captureAfter() {
        PendingEntityMutation pending = this.luma$pendingEntityMutation.get();
        this.luma$pendingEntityMutation.remove();
        EntityMutationTracker.captureAfter((Entity) (Object) this, pending);
    }
}
