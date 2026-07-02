package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.WorldMutationContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerLevel.class)
abstract class ServerLevelEntityTickMixin {

    @Unique
    private static final EntityCausalContextRegistry LUMA_ENTITY_CAUSAL_CONTEXTS =
            EntityCausalContextRegistry.getInstance();

    @WrapMethod(method = "tickNonPassenger")
    private void luma$wrapEntityTick(Entity entity, Operation<Void> original) {
        WorldMutationSource source = this.luma$sourceForTrackedEntity(entity);
        if (source == null) {
            original.call(entity);
            return;
        }

        try (WorldMutationContext.SourceFrame ignored = this.luma$pushEntityTickSource(entity, source)) {
            EntityCausalContextRegistry.ContextFrame causalFrame =
                    this.luma$pushRememberedCausalMobAction(entity, source);
            try {
                original.call(entity);
            } finally {
                if (causalFrame != null) {
                    causalFrame.close();
                }
            }
        }
    }

    @Unique
    private WorldMutationContext.SourceFrame luma$pushEntityTickSource(Entity entity, WorldMutationSource source) {
        ServerPlayer player = source == WorldMutationSource.MOB ? this.luma$causalTargetPlayer(entity) : null;
        if (player != null) {
            WorldMutationContext.SourceFrame frame = WorldMutationContext.pushPlayerSource(
                    WorldMutationSource.MOB,
                    player.getName().getString(),
                    LumaAccessControl.getInstance().canUse(player) || WorldMutationContext.currentAccessAllowed()
            );
            LUMA_ENTITY_CAUSAL_CONTEXTS.rememberCurrentPlayerActionIfAbsent(entity, (ServerLevel) (Object) this);
            return frame;
        }
        return WorldMutationContext.pushSource(source);
    }

    @Unique
    private ServerPlayer luma$causalTargetPlayer(Entity entity) {
        if (entity instanceof Mob mob && mob.getTarget() instanceof ServerPlayer player) {
            return player;
        }
        if (entity instanceof Projectile projectile
                && projectile.getOwner() instanceof Mob owner
                && owner.getTarget() instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }

    @Unique
    private EntityCausalContextRegistry.ContextFrame luma$pushRememberedCausalMobAction(
            Entity entity,
            WorldMutationSource source
    ) {
        if (source != WorldMutationSource.MOB) {
            return null;
        }
        return LUMA_ENTITY_CAUSAL_CONTEXTS.pushIfPresent(entity, (ServerLevel) (Object) this, source);
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
        if (entity instanceof Mob
                || entity instanceof EnderDragon
                || entity instanceof WitherSkull
                || entity instanceof Projectile projectile && projectile.getOwner() instanceof Mob) {
            return WorldMutationSource.MOB;
        }
        return null;
    }
}
