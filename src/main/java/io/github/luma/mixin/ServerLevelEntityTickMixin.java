package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerLevel.class)
abstract class ServerLevelEntityTickMixin {

    @WrapMethod(method = "tickNonPassenger")
    private void luma$wrapEntityTick(Entity entity, Operation<Void> original) {
        WorldMutationSource source = this.luma$sourceForTrackedEntity(entity);
        if (source == null) {
            original.call(entity);
            return;
        }

        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushSource(source)) {
            original.call(entity);
        }
    }

    @Unique
    private WorldMutationSource luma$sourceForTrackedEntity(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return null;
        }
        if (entity instanceof FallingBlockEntity) {
            return WorldMutationSource.FALLING_BLOCK;
        }
        if (entity instanceof AbstractMinecart) {
            return WorldMutationSource.BLOCK_UPDATE;
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
