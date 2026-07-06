package io.github.luma.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.ExplosiveSourceContextPolicy;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.ExactReplayStateGuard;
import io.github.luma.minecraft.world.TntReplayActivationPolicy;
import io.github.luma.minecraft.world.WorldReplayTickSuppression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(TntBlock.class)
abstract class TntBlockMixin {

    @Unique
    private static final TntReplayActivationPolicy LUMA_REPLAY_ACTIVATION_POLICY =
            new TntReplayActivationPolicy();
    @Unique
    private static final ExactReplayStateGuard LUMA_EXACT_REPLAY_STATE_GUARD =
            ExactReplayStateGuard.getInstance();
    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();
    @Unique
    private static final ExplosiveSourceContextPolicy LUMA_EXPLOSIVE_SOURCE_CONTEXT_POLICY =
            new ExplosiveSourceContextPolicy();

    @WrapMethod(method = "onPlace")
    private void luma$wrapOnPlace(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState oldState,
            boolean moved,
            Operation<Void> original
    ) {
        if (this.luma$shouldSuppressReplayActivation(level, pos, "onPlace")) {
            return;
        }
        WorldMutationContext.SourceFrame frame = this.luma$pushExplosiveSource(level);
        try {
            original.call(state, level, pos, oldState, moved);
        } finally {
            this.luma$closeSource(frame);
        }
    }

    @WrapMethod(method = "neighborChanged")
    private void luma$wrapNeighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block sourceBlock,
            Orientation orientation,
            boolean movedByPiston,
            Operation<Void> original
    ) {
        if (this.luma$shouldSuppressReplayActivation(level, pos, "neighborChanged")) {
            return;
        }
        WorldMutationContext.SourceFrame frame = this.luma$pushExplosiveSource(level);
        try {
            original.call(state, level, pos, sourceBlock, orientation, movedByPiston);
        } finally {
            this.luma$closeSource(frame);
        }
    }

    @WrapMethod(method = "useItemOn")
    private InteractionResult luma$wrapUseItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult,
            Operation<InteractionResult> original
    ) {
        WorldMutationContext.SourceFrame frame = this.luma$pushExplosiveSource(level);
        try {
            return original.call(stack, state, level, pos, player, hand, hitResult);
        } finally {
            this.luma$closeSource(frame);
        }
    }

    @WrapMethod(method = "onProjectileHit")
    private void luma$wrapProjectileHit(
            Level level,
            BlockState state,
            BlockHitResult hitResult,
            Projectile projectile,
            Operation<Void> original
    ) {
        WorldMutationContext.SourceFrame frame = this.luma$pushExplosiveSource(level);
        try {
            original.call(level, state, hitResult, projectile);
        } finally {
            this.luma$closeSource(frame);
        }
    }

    @WrapMethod(method = "prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)Z")
    private static boolean luma$wrapPrime(
            Level level,
            BlockPos pos,
            LivingEntity owner,
            Operation<Boolean> original
    ) {
        if (luma$shouldSuppressReplayActivation(level, pos, "prime")) {
            return false;
        }
        WorldMutationContext.SourceFrame frame = luma$pushExplosiveSource(level);
        try {
            return original.call(level, pos, owner);
        } finally {
            luma$closeSource(frame);
        }
    }

    @WrapMethod(method = "wasExploded")
    private void luma$wrapWasExploded(
            ServerLevel level,
            BlockPos pos,
            Explosion explosion,
            Operation<Void> original
    ) {
        if (this.luma$shouldSuppressReplayActivation(level, pos, "wasExploded")) {
            return;
        }
        WorldMutationContext.SourceFrame frame = this.luma$pushExplosiveSource(level);
        try {
            original.call(level, pos, explosion);
        } finally {
            this.luma$closeSource(frame);
        }
    }

    @Unique
    private static WorldMutationContext.SourceFrame luma$pushExplosiveSource(Level level) {
        if (level.isClientSide()) {
            return null;
        }
        if (!LUMA_EXPLOSIVE_SOURCE_CONTEXT_POLICY.canOpenExplosiveSource()) {
            return null;
        }

        return WorldMutationContext.pushSource(WorldMutationSource.EXPLOSIVE);
    }

    @Unique
    private static boolean luma$shouldSuppressReplayActivation(Level level, BlockPos pos, String callback) {
        boolean guarded = luma$isReplayGuarded(level, pos);
        boolean frozen = luma$isFrozen(level);
        boolean suppressed = frozen || LUMA_REPLAY_ACTIVATION_POLICY.shouldSuppressActivation(
                level.isClientSide(),
                WorldMutationContext.currentSource(),
                WorldMutationContext.captureSuppressed(),
                guarded
        );
        luma$logActivation(callback, level, pos, suppressed, guarded, frozen);
        return suppressed;
    }

    @Unique
    private static boolean luma$isReplayGuarded(Level level, BlockPos pos) {
        return level instanceof ServerLevel serverLevel
                && LUMA_EXACT_REPLAY_STATE_GUARD.guards(serverLevel, pos);
    }

    @Unique
    private static boolean luma$isFrozen(Level level) {
        return level instanceof ServerLevel serverLevel
                && LUMA_REPLAY_TICK_SUPPRESSION.shouldFreezeWorldTick(serverLevel);
    }

    @Unique
    private static void luma$logActivation(
            String callback,
            Level level,
            BlockPos pos,
            boolean suppressed,
            boolean guarded,
            boolean frozen
    ) {
        if (level.isClientSide() || (!suppressed && !guarded && !frozen && !WorldMutationContext.captureSuppressed())) {
            return;
        }
        long time = level instanceof ServerLevel serverLevel ? serverLevel.getGameTime() : -1;
        LumaLoadLog.event("tnt-replay", "activation",
                "callback=" + callback
                        + ", suppressed=" + suppressed
                        + ", guarded=" + guarded
                        + ", frozen=" + frozen
                        + ", time=" + time
                        + ", source=" + WorldMutationContext.currentSource()
                        + ", action=" + WorldMutationContext.currentActionId()
                        + ", actor=" + WorldMutationContext.currentActor()
                        + ", captureSuppressed=" + WorldMutationContext.captureSuppressed()
                        + ", pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    @Unique
    private static void luma$closeSource(WorldMutationContext.SourceFrame frame) {
        if (frame != null) {
            frame.close();
        }
    }
}
