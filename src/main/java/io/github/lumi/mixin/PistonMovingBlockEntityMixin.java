package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMovingBlockEntityMixin {
    @Unique private static final ThreadLocal<Deque<Optional<DirectLiveActionContext.Scope>>>
            LUMI_SCOPES = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "tick", at = @At("HEAD"))
    private static void lumi$beginCarrierTick(
            Level level, BlockPos position, BlockState state,
            PistonMovingBlockEntity carrier, CallbackInfo callback) {
        Optional<DirectLiveActionContext.Scope> scope = level instanceof ServerLevel serverLevel
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
        Deque<Optional<DirectLiveActionContext.Scope>> scopes = LUMI_SCOPES.get();
        scopes.removeLast().ifPresent(DirectLiveActionContext.Scope::close);
        if (scopes.isEmpty()) {
            LUMI_SCOPES.remove();
        }
    }
}
