package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.axiom.ContextMenuManager;
import io.github.lumi.client.ui.LumiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A detached Axiom menu must not grab the mouse from an independent Lumi modal. */
@Mixin(value = ContextMenuManager.class, remap = false)
abstract class AxiomContextMenuMixin {
    @WrapOperation(method = "close", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;grabMouse()V", remap = true))
    private void lumi$preserveLumiScreen(MouseHandler mouse, Operation<Void> original) {
        if (!(Minecraft.getInstance().screen instanceof LumiScreen)) original.call(mouse);
    }
}
