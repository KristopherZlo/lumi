package io.github.luma.mixin.client;

import io.github.luma.client.selection.LumiRegionSelectionController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public final class MouseHandlerMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void lumi$handleSelectionButton(long window, MouseButtonInfo button, int action, CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        if (this.sameWindow(client, window)
                && LumiRegionSelectionController.getInstance().handleMouseButton(
                client,
                button.button(),
                action,
                button.modifiers()
        )) {
            callback.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void lumi$handleSelectionScroll(long window, double horizontalAmount, double verticalAmount, CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        if (this.sameWindow(client, window)
                && LumiRegionSelectionController.getInstance().handleScroll(client, horizontalAmount, verticalAmount)) {
            callback.cancel();
        }
    }

    private boolean sameWindow(Minecraft client, long window) {
        return client != null && client.getWindow() != null && client.getWindow().handle() == window;
    }
}
