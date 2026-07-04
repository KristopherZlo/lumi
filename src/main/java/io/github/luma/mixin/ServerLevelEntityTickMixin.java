package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.EntityCausalContextRegistry;
import io.github.luma.minecraft.capture.ExplosiveEntityContextRegistry;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldReplayTickSuppression;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
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
    @Unique
    private static final ExplosiveEntityContextRegistry LUMA_EXPLOSIVE_CONTEXTS =
            ExplosiveEntityContextRegistry.getInstance();
    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();

    @WrapMethod(method = "tickNonPassenger")
    private void luma$wrapEntityTick(Entity entity, Operation<Void> original) {
        if (this.luma$shouldFreezePrimedTnt(entity)) {
            return;
        }
        WorldMutationSource source = this.luma$sourceForTrackedEntity(entity);
        if (source == null) {
            original.call(entity);
            return;
        }

        boolean explosiveFrame = this.luma$pushRememberedExplosiveAction(entity);
        EntityCausalContextRegistry.ContextFrame causalFrame = explosiveFrame
                ? null
                : this.luma$pushRememberedCausalMobAction(entity, source);
        WorldMutationContext.SourceFrame sourceFrame = null;
        try {
            if (!explosiveFrame && (causalFrame == null || !causalFrame.active())) {
                sourceFrame = this.luma$pushEntityTickSource(entity, source);
            }
            original.call(entity);
        } finally {
            if (sourceFrame != null) {
                sourceFrame.close();
            }
            if (causalFrame != null) {
                causalFrame.close();
            }
            if (explosiveFrame) {
                WorldMutationContext.popSource();
            }
        }
    }

    @Unique
    private WorldMutationContext.SourceFrame luma$pushEntityTickSource(Entity entity, WorldMutationSource source) {
        return WorldMutationContext.pushSource(source);
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
    private boolean luma$pushRememberedExplosiveAction(Entity entity) {
        return entity instanceof PrimedTnt && LUMA_EXPLOSIVE_CONTEXTS.pushContext(entity);
    }

    @Unique
    private boolean luma$shouldFreezePrimedTnt(Entity entity) {
        return entity instanceof PrimedTnt
                && LUMA_REPLAY_TICK_SUPPRESSION.shouldFreezeWorldTick((ServerLevel) (Object) this);
    }

    @Unique
    private WorldMutationSource luma$sourceForTrackedEntity(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return null;
        }
        if (entity instanceof PrimedTnt) {
            return WorldMutationSource.EXPLOSIVE;
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
