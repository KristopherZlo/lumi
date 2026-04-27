package io.github.luma.ui.framework.component;

import io.github.luma.ui.framework.core.Sizing;
import io.github.luma.ui.framework.core.UIComponent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class TextureComponent extends UIComponent {

    private final Identifier texture;
    private final int textureWidth;
    private final int textureHeight;

    TextureComponent(
            Identifier texture,
            int u,
            int v,
            int regionWidth,
            int regionHeight,
            int textureWidth,
            int textureHeight
    ) {
        this.texture = texture;
        this.textureWidth = Math.max(1, textureWidth);
        this.textureHeight = Math.max(1, textureHeight);
        this.sizing(Sizing.fixed(Math.max(1, regionWidth)), Sizing.fixed(Math.max(1, regionHeight)));
    }

    @Override
    public int measureWidth(int availableWidth) {
        return Math.min(this.horizontalSizing().resolve(availableWidth, 0), availableWidth);
    }

    @Override
    public int measureHeight(int availableWidth, int availableHeight) {
        return this.verticalSizing().resolve(availableHeight, 0);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                this.texture,
                this.x(),
                this.y(),
                0.0F,
                0.0F,
                this.width(),
                this.height(),
                this.textureWidth,
                this.textureHeight
        );
    }
}
