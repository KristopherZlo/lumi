package io.github.lumi.gametest.mixin;

import io.github.lumi.client.ui.LumiScreen;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retains the native caller when a test unexpectedly loses a Lumi screen. */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.gui.Gui.class)
*///?} else {
@Mixin(Minecraft.class)
//?}
public abstract class ClientScreenTraceMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void lumi$traceScreen(Screen next, CallbackInfo callback) {
        Screen previous = Minecraft.getInstance().screen;
        if (!(previous instanceof LumiScreen) && !(next instanceof LumiScreen)) return;
        String caller = StackWalker.getInstance().walk(frames -> frames.skip(1).limit(12)
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                .collect(Collectors.joining(" <- ")));
        LoggerFactory.getLogger("Lumi/GameTestScreen").info("Screen {} -> {}; caller={}",
                previous == null ? "null" : previous.getClass().getSimpleName(),
                next == null ? "null" : next.getClass().getSimpleName(), caller);
    }
}
