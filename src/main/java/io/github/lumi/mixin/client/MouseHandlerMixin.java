package io.github.lumi.mixin.client;

import io.github.lumi.client.LumiSelectionTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses vanilla actions consumed by the wooden-sword selection tool. */
@Mixin(MouseHandler.class)
public final class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void lumi$selectionButton(
            long window, MouseButtonInfo button, int action, CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        if (sameWindow(client, window)
                && LumiSelectionTool.handleMouseButton(
                        client, button.button(), action, button.modifiers())) {
            callback.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void lumi$selectionScroll(
            long window, double horizontal, double vertical, CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        if (sameWindow(client, window)
                && LumiSelectionTool.handleScroll(client, horizontal, vertical)) {
            callback.cancel();
        }
    }

    private static boolean sameWindow(Minecraft client, long window) {
        return client != null && client.getWindow() != null
                && client.getWindow().handle() == window;
    }
}
