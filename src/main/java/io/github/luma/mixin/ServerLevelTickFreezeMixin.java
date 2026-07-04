package io.github.luma.mixin;

import io.github.luma.minecraft.world.WorldReplayTickSuppression;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelTickFreezeMixin {

    @Unique
    private static final WorldReplayTickSuppression LUMA_REPLAY_TICK_SUPPRESSION =
            WorldReplayTickSuppression.getInstance();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void luma$freezeWorldTick(BooleanSupplier hasTimeLeft, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (LUMA_REPLAY_TICK_SUPPRESSION.shouldFreezeWorldTick(level)) {
            LUMA_REPLAY_TICK_SUPPRESSION.logFrozenWorldTick(level);
            callback.cancel();
        }
    }
}
