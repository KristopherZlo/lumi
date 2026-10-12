package io.github.lumi.client.ui;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Vector2ic;

/** Keeps native tooltip placement inside Lumi's virtual viewport. */
public record LumiTooltipPositioner(
        ClientTooltipPositioner delegate, int viewportWidth, int viewportHeight)
        implements ClientTooltipPositioner {
    @Override
    public Vector2ic positionTooltip(
            int screenWidth, int screenHeight, int mouseX, int mouseY,
            int tooltipWidth, int tooltipHeight) {
        return delegate.positionTooltip(
                viewportWidth, viewportHeight, mouseX, mouseY, tooltipWidth, tooltipHeight);
    }
}
