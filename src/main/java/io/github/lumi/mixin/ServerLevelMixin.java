package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Unique private final Deque<Optional<DirectLiveActionContext.Scope>> lumi$causalTicks =
            new ArrayDeque<>();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lumi$freezeDimensionSimulation(BooleanSupplier hasTimeLeft, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
            if (runtime.freeze().isFrozen()) {
                callback.cancel();
            }
        });
    }

    @Inject(method = "tickBlock", at = @At("HEAD"))
    private void lumi$beginBlockTick(BlockPos position, Block block, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        lumi$causalTicks.addLast(LumiMod.serverRuntime().find(level)
                .flatMap(runtime -> runtime.causalTicks().resumeBlock(position, block)));
    }

    @Inject(method = "tickBlock", at = @At("RETURN"))
    private void lumi$endBlockTick(BlockPos position, Block block, CallbackInfo callback) {
        lumi$endCausalTick();
    }

    @Inject(method = "tickFluid", at = @At("HEAD"))
    private void lumi$beginFluidTick(BlockPos position, Fluid fluid, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        lumi$causalTicks.addLast(LumiMod.serverRuntime().find(level)
                .flatMap(runtime -> runtime.causalTicks().resumeFluid(position, fluid)));
    }

    @Inject(method = "tickFluid", at = @At("RETURN"))
    private void lumi$endFluidTick(BlockPos position, Fluid fluid, CallbackInfo callback) {
        lumi$endCausalTick();
    }

    @Unique
    private void lumi$endCausalTick() {
        lumi$causalTicks.removeLast().ifPresent(DirectLiveActionContext.Scope::close);
    }
}
