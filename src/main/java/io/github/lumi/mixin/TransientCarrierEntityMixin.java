package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.MinecraftCausalTickTracker;
import io.github.lumi.minecraft.runtime.MinecraftLiveEntityTracker;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({
        FallingBlockEntity.class,
        ItemEntity.class,
        PrimedTnt.class,
        AbstractArrow.class,
        AbstractMinecart.class
})
abstract class TransientCarrierEntityMixin {
    @Unique private Optional<MinecraftCausalTickTracker.CausalExecution> lumi$scope = Optional.empty();
    @Unique private Optional<MinecraftLiveEntityTracker.Pending> lumi$entity = Optional.empty();

    @Inject(method = "tick", at = @At("HEAD"))
    private void lumi$beginCarrierTick(CallbackInfo callback) {
        Entity carrier = (Entity) (Object) this;
        if (carrier.level() instanceof ServerLevel level) {
            LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
                lumi$scope = runtime.causalTicks().resumeCarrier(carrier);
                if (lumi$scope.isPresent() && carrier instanceof AbstractMinecart) {
                    try {
                        lumi$entity = runtime.liveEntities().begin(carrier);
                    } catch (IOException failed) {
                        LumiMod.LOGGER.warn(
                                "Cannot capture causal entity {} before tick",
                                carrier.getUUID(), failed);
                    }
                }
            });
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void lumi$endCarrierTick(CallbackInfo callback) {
        Entity carrier = (Entity) (Object) this;
        if (carrier.level() instanceof ServerLevel level) {
            LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
                boolean changed = lumi$scope.isPresent();
                try {
                    if (lumi$entity.isPresent()) {
                        changed = runtime.liveEntities().finish(
                                lumi$entity.orElseThrow());
                    }
                } catch (IOException failed) {
                    LumiMod.LOGGER.warn(
                            "Cannot capture causal entity {} after tick",
                            carrier.getUUID(), failed);
                }
                runtime.causalTicks().finishedCarrier(carrier, changed);
            });
        }
        lumi$scope.ifPresent(MinecraftCausalTickTracker.CausalExecution::close);
        lumi$scope = Optional.empty();
        lumi$entity = Optional.empty();
    }
}
