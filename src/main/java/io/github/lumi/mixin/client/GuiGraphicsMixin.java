package io.github.lumi.mixin.client;

import io.github.lumi.client.ui.LumiScreen;
import io.github.lumi.client.ui.LumiTooltipPositioner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Adapts native tooltip bounds while a Lumi screen owns the viewport. */
@Mixin(GuiGraphics.class)
public final class GuiGraphicsMixin {
    @ModifyVariable(method = "renderTooltip", at = @At("HEAD"), argsOnly = true)
    private ClientTooltipPositioner lumi$tooltipViewport(ClientTooltipPositioner positioner) {
        if (Minecraft.getInstance().screen instanceof LumiScreen screen) {
            return new LumiTooltipPositioner(positioner, screen.width, screen.height);
        }
        return positioner;
    }
}
