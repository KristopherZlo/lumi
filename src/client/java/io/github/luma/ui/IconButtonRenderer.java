package io.github.luma.ui;

import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

final class IconButtonRenderer implements ButtonComponent.Renderer {

    private static final int TEXTURE_SIZE = 24;
    private static final int DRAW_SIZE = 16;

    private final Identifier icon;
    private final Identifier disabledIcon;
    private final int fill;
    private final int hover;
    private final int disabled;

    IconButtonRenderer(Identifier icon, Identifier disabledIcon, int fill, int hover, int disabled) {
        this.icon = icon;
        this.disabledIcon = disabledIcon;
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
        int iconX = button.getX() + ((button.getWidth() - DRAW_SIZE) / 2);
        int iconY = button.getY() + ((button.getHeight() - DRAW_SIZE) / 2);
        context.blit(RenderPipelines.GUI_TEXTURED, button.active() ? this.icon : this.disabledIcon, iconX, iconY, 0, 0, DRAW_SIZE, DRAW_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
    }
}
