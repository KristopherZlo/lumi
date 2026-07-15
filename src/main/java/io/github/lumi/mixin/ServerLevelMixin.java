package io.github.lumi.mixin;

import io.github.lumi.LumiMod;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lumi$freezeDimensionSimulation(BooleanSupplier hasTimeLeft, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        LumiMod.serverRuntime().find(level).ifPresent(runtime -> {
            if (runtime.freeze().isFrozen()) {
                callback.cancel();
            }
        });
    }
}
