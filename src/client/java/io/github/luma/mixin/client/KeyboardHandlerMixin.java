package io.github.luma.mixin.client;

import io.github.luma.client.input.BranchSwitchHotkeyController;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public final class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void lumi$handleBranchSwitchHotkey(
            long window,
            int key,
            int scanCode,
            int action,
            int modifiers,
            CallbackInfo callback
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null || client.getWindow().handle() != window) {
            return;
        }
        if (BranchSwitchHotkeyController.getInstance().handleKeyPress(client, key, scanCode, action, modifiers)) {
            callback.cancel();
        }
    }
}
