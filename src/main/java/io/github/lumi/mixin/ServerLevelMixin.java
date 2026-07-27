package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.MinecraftCausalTickTracker;
import io.github.lumi.minecraft.world.MinecraftEntityChunkCapture;
import io.github.lumi.minecraft.world.OwnedBlockEventAccess;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin implements OwnedBlockEventAccess {
    @Shadow @Final private ObjectLinkedOpenHashSet<BlockEventData> blockEvents;
    @Shadow @Final private List<BlockEventData> blockEventsToReschedule;
    @Unique private final Deque<Optional<MinecraftCausalTickTracker.CausalExecution>> lumi$causalTicks =
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
        lumi$finishLiveBlockEntity(position);
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

    @Inject(method = "blockEvent", at = @At("HEAD"))
    private void lumi$captureBlockEvent(
            BlockPos position, Block block, int paramA, int paramB, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        LumiMod.serverRuntime().find(level).ifPresent(runtime ->
                runtime.causalTicks().scheduledBlockEvent(
                        new BlockEventData(position, block, paramA, paramB)));
    }

    @Inject(method = "doBlockEvent", at = @At("HEAD"))
    private void lumi$beginBlockEvent(
            BlockEventData event,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        lumi$causalTicks.addLast(LumiMod.serverRuntime().find(level)
                .flatMap(runtime -> runtime.causalTicks().resumeBlockEvent(event)));
    }

    @Inject(method = "doBlockEvent", at = @At("RETURN"))
    private void lumi$endBlockEvent(
            BlockEventData event,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> callback) {
        lumi$finishLiveBlockEntity(event.pos());
        lumi$endCausalTick();
    }

    @Inject(method = "addEntity", at = @At("RETURN"))
    private void lumi$captureEntityCarrier(
            Entity entity,
            CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue()) {
            ServerLevel level = (ServerLevel) (Object) this;
            LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
                runtime.causalTicks().rememberCarrier(entity);
                try {
                    runtime.entityAdded(entity);
                } catch (IOException failed) {
                    LumiMod.LOGGER.warn("Cannot register added durable entity {}",
                            entity.getUUID(), failed);
                }
                try {
                    runtime.liveEntities().added(entity);
                } catch (IOException failed) {
                    LumiMod.LOGGER.warn("Cannot capture added live entity {}",
                            entity.getUUID(), failed);
                }
            });
        }
    }

    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void lumi$rejectFrozenEntityAdd(
            Entity entity, CallbackInfoReturnable<Boolean> callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
            if (MinecraftEntityChunkCapture.isDurableRoot(entity)
                    && !runtime.freeze().isMutationAllowed()) {
                callback.setReturnValue(false);
            }
        });
    }

    @Override
    public boolean lumi$hasBlockEvent(BlockEventData event) {
        return blockEvents.contains(event) || blockEventsToReschedule.contains(event);
    }

    @Override
    public void lumi$removeBlockEvent(BlockEventData event) {
        blockEvents.remove(event);
        blockEventsToReschedule.remove(event);
    }

    @Override
    public void lumi$removeBlockEventsWhere(Predicate<BlockPos> matches) {
        blockEvents.removeIf(event -> matches.test(event.pos()));
        blockEventsToReschedule.removeIf(event -> matches.test(event.pos()));
    }

    @Unique
    private void lumi$endCausalTick() {
        lumi$causalTicks.removeLast().ifPresent(MinecraftCausalTickTracker.CausalExecution::close);
    }

    @Unique
    private void lumi$finishLiveBlockEntity(BlockPos position) {
        ServerLevel level = (ServerLevel) (Object) this;
        var blockEntity = level.getBlockEntity(position);
        if (blockEntity == null) {
            return;
        }
        LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
            try {
                if (runtime.liveBlockEntities().changed(blockEntity)) {
                    runtime.causalTicks().rememberCarrier(blockEntity);
                }
            } catch (IOException failed) {
                LumiMod.LOGGER.warn("Cannot finish causal block entity {}",
                        position, failed);
            }
        });
    }
}
