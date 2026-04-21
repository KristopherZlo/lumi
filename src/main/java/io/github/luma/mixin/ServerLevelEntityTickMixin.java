package io.github.luma.mixin;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
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
        WorldMutationSource source = this.luma$sourceForTrackedEntity(entity);
        if (source == null) {
            return;
        }

        this.luma$entityMutationDepth += 1;
        WorldMutationContext.pushSource(source);
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void luma$endEntityTick(Entity entity, CallbackInfo ci) {
        if (this.luma$sourceForTrackedEntity(entity) == null || this.luma$entityMutationDepth <= 0) {
            return;
        }

        this.luma$entityMutationDepth -= 1;
        WorldMutationContext.popSource();
    }

    @Unique
    private WorldMutationSource luma$sourceForTrackedEntity(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return null;
        }
        if (entity instanceof FallingBlockEntity) {
            return WorldMutationSource.FALLING_BLOCK;
        }
        if (entity instanceof EnderMan
                || entity instanceof Creeper
                || entity instanceof Ravager
                || entity instanceof WitherBoss
                || entity instanceof WitherSkull) {
            return WorldMutationSource.MOB;
        }
        return null;
    }
}
