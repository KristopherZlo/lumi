package io.github.lumi.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.MultipleTestTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Uses vanilla reporting, shutdown and the failed-test exit code after the first required failure. */
@Mixin(GameTestServer.class)
abstract class GameTestServerFailFastMixin {
    @Redirect(method = "tickServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/gametest/framework/MultipleTestTracker;isDone()Z"))
    private boolean finishAfterRequiredFailure(MultipleTestTracker tests) {
        return tests.isDone() || tests.hasFailedRequired();
    }
}
