package io.github.luma.ui;

import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

final class IconButtonRenderer implements ButtonComponent.Renderer {

    private final Identifier icon;
    private final int fill;
    private final int hover;
    private final int disabled;

    IconButtonRenderer(Identifier icon, int fill, int hover, int disabled) {
        this.icon = icon;
        this.fill = fill;
        this.hover = hover;
        this.disabled = disabled;
    }

    @Override
    public void draw(OwoUIGraphics context, ButtonComponent button, float delta) {
        int color = button.active()
                ? button.isHovered() ? this.hover : this.fill
                : this.disabled;
        context.fill(button.getX(), button.getY(), button.getX() + button.getWidth(), button.getY() + button.getHeight(), color);
        int iconX = button.getX() + ((button.getWidth() - 16) / 2);
        int iconY = button.getY() + ((button.getHeight() - 16) / 2);
        context.blit(RenderPipelines.GUI_TEXTURED, this.icon, iconX, iconY, 0, 0, 16, 16, 16, 16, 16, 16);
    }
}
