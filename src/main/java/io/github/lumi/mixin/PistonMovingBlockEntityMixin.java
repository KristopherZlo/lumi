package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.MinecraftCausalTickTracker;
import io.github.lumi.minecraft.runtime.MinecraftLiveEntityTracker;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMovingBlockEntityMixin {
    @Unique private static final ThreadLocal<Deque<Optional<MinecraftCausalTickTracker.CausalExecution>>>
            LUMI_SCOPES = ThreadLocal.withInitial(ArrayDeque::new);

    @WrapOperation(method = "moveCollidedEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(DDD)V"))
    private static void lumi$capturePistonVelocity(
            Entity entity, double x, double y, double z, Operation<Void> original) {
        lumi$capturePistonChange(entity, () -> original.call(entity, x, y, z));
    }

    @WrapOperation(method = "moveEntityByPiston", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;move("
                    + "Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private static void lumi$capturePistonPosition(
            Entity entity, MoverType movement, Vec3 distance, Operation<Void> original) {
        lumi$capturePistonChange(entity, () -> original.call(entity, movement, distance));
    }

    /** Capture before native velocity changes: the next entity tick is already too late. */
    @Unique
    private static void lumi$capturePistonChange(Entity entity, Runnable change) {
        var runtime = entity.level() instanceof ServerLevel level
                ? LumiMod.serverRuntime().find(level).orElse(null) : null;
        if (runtime == null) {
            change.run();
            return;
        }
        runtime.causalTicks().rememberCarrier(entity);
        Optional<MinecraftLiveEntityTracker.Pending> pending = Optional.empty();
        try {
            pending = runtime.liveEntities().begin(entity);
        } catch (IOException failed) {
            DirectLiveActionContext.current(runtime.liveActions()).ifPresent(action ->
                    runtime.liveActions().makeUnavailable(action, "Cannot capture entity before piston movement"));
            LumiMod.LOGGER.warn("Cannot capture entity {} before piston movement", entity.getUUID(), failed);
        }
        try {
            change.run();
        } finally {
            if (pending.isPresent()) {
                try {
                    runtime.liveEntities().finish(pending.orElseThrow());
                } catch (IOException failed) {
                    DirectLiveActionContext.current(runtime.liveActions()).ifPresent(action ->
                            runtime.liveActions().makeUnavailable(action, "Cannot capture entity after piston movement"));
                    LumiMod.LOGGER.warn("Cannot capture entity {} after piston movement", entity.getUUID(), failed);
                }
            }
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private static void lumi$beginCarrierTick(
            Level level, BlockPos position, BlockState state,
            PistonMovingBlockEntity carrier, CallbackInfo callback) {
        Optional<MinecraftCausalTickTracker.CausalExecution> scope = level instanceof ServerLevel serverLevel
                ? LumiMod.serverRuntime().find(serverLevel)
                        .flatMap(runtime -> runtime.causalTicks().resumeCarrier(carrier))
                : Optional.empty();
        LUMI_SCOPES.get().addLast(scope);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private static void lumi$endCarrierTick(
            Level level, BlockPos position, BlockState state,
            PistonMovingBlockEntity carrier, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) {
            LumiMod.serverRuntime().find(serverLevel).ifPresent(runtime ->
                    runtime.causalTicks().finishedCarrier(carrier));
        }
        Deque<Optional<MinecraftCausalTickTracker.CausalExecution>> scopes = LUMI_SCOPES.get();
        scopes.removeLast().ifPresent(MinecraftCausalTickTracker.CausalExecution::close);
        if (scopes.isEmpty()) {
            LUMI_SCOPES.remove();
        }
    }
}
