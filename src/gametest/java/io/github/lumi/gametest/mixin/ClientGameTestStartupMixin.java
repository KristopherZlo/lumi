package io.github.lumi.gametest.mixin;

import java.util.concurrent.Phaser;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers Fabric's test party before the client can observe the test thread. */
@Mixin(targets = "net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl", remap = false)
public abstract class ClientGameTestStartupMixin {
    @Shadow @Final public static Phaser PHASER;

    @Inject(method = "runTestThread", at = @At(value = "FIELD",
            target = "Lnet/fabricmc/fabric/impl/client/gametest/threading/ThreadingImpl;testThread:Ljava/lang/Thread;",
            opcode = Opcodes.PUTSTATIC))
    private static void lumi$registerBeforePublication(Runnable test, CallbackInfo callback) {
        PHASER.register();
    }

    @Redirect(method = "lambda$runTestThread$0", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/Phaser;register()I"))
    private static int lumi$alreadyRegistered(Phaser phaser) throws InterruptedException {
        // A delayed first test instruction reproduces the original registration race.
        if (Boolean.getBoolean("lumi.gametest.delayStartup")) Thread.sleep(500);
        return phaser.getPhase();
    }
}
