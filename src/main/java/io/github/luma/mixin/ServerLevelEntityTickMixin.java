package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelEntityTickMixin {

    @Unique
    private int luma$entityMutationDepth = 0;

    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void luma$beginEntityTick(Entity entity, CallbackInfo ci) {
        if (!this.luma$shouldTrackEntityMutation(entity)) {
            return;
        }

        this.luma$entityMutationDepth += 1;
        WorldMutationContext.pushSource(WorldMutationSource.ENTITY);
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void luma$endEntityTick(Entity entity, CallbackInfo ci) {
        if (!this.luma$shouldTrackEntityMutation(entity) || this.luma$entityMutationDepth <= 0) {
            return;
        }

        this.luma$entityMutationDepth -= 1;
        WorldMutationContext.popSource();
    }

    @Unique
    private boolean luma$shouldTrackEntityMutation(Entity entity) {
        return !(entity instanceof ServerPlayer)
                && (entity instanceof EnderMan || entity instanceof FallingBlockEntity);
    }
}
