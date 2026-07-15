package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.MinecraftCausalTickTracker;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({FallingBlockEntity.class, PrimedTnt.class})
abstract class TransientCarrierEntityMixin {
    @Unique private Optional<MinecraftCausalTickTracker.CausalExecution> lumi$scope = Optional.empty();

    @Inject(method = "tick", at = @At("HEAD"))
    private void lumi$beginCarrierTick(CallbackInfo callback) {
        Entity carrier = (Entity) (Object) this;
        if (carrier.level() instanceof ServerLevel level) {
            lumi$scope = LumiMod.serverRuntime().find(level)
                    .flatMap(runtime -> runtime.causalTicks().resumeCarrier(carrier));
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void lumi$endCarrierTick(CallbackInfo callback) {
        Entity carrier = (Entity) (Object) this;
        if (carrier.level() instanceof ServerLevel level) {
            LumiMod.serverRuntime().find(level).ifPresent(runtime ->
                    runtime.causalTicks().finishedCarrier(carrier));
        }
        lumi$scope.ifPresent(MinecraftCausalTickTracker.CausalExecution::close);
        lumi$scope = Optional.empty();
    }
}
