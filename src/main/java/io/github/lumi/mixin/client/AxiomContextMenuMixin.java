package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.axiom.ContextMenuManager;
import com.moulberry.axiom.screen.SwitchBuilderToolScreen;
import com.moulberry.axiom.screen.SwitchHotbarScreen;
import io.github.lumi.client.LumiSelectionTool;
import io.github.lumi.client.ui.LumiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives Lumi's selection tool and modal screens priority over detached Axiom menus. */
@Mixin(value = ContextMenuManager.class, remap = false)
abstract class AxiomContextMenuMixin {
    @Inject(method = "open", at = @At("HEAD"), cancellable = true)
    private void lumi$reserveSelectionModifier(Screen screen, CallbackInfo callback) {
        if ((screen instanceof SwitchHotbarScreen || screen instanceof SwitchBuilderToolScreen)
                && LumiSelectionTool.held(Minecraft.getInstance())) callback.cancel();
    }

    @WrapOperation(method = "close", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;grabMouse()V", remap = true))
    private void lumi$preserveLumiScreen(MouseHandler mouse, Operation<Void> original) {
        if (!(Minecraft.getInstance().screen instanceof LumiScreen)) original.call(mouse);
    }
}
