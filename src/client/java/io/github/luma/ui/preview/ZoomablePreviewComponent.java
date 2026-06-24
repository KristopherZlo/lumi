package io.github.luma.ui.preview;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.function.BiConsumer;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class ZoomablePreviewComponent extends BaseUIComponent {

    private final Identifier textureId;
    private final int imageWidth;
    private final int imageHeight;
    private final int zoomStep;
    private final BiConsumer<Integer, Integer> panChanged;
    private int panX;
    private int panY;

    public ZoomablePreviewComponent(
            Identifier textureId,
            int imageWidth,
            int imageHeight,
            int width,
            int height,
            int zoomStep,
            int panX,
            int panY,
            BiConsumer<Integer, Integer> panChanged
    ) {
        this.textureId = textureId;
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
        this.zoomStep = Math.max(0, zoomStep);
        this.panChanged = panChanged;
        this.panX = panX;
        this.panY = panY;
        this.sizing(Sizing.fixed(width), Sizing.fixed(height));
    }

    @Override
    public void update(float delta, int mouseX, int mouseY) {
        super.update(delta, mouseX, mouseY);
        this.cursorStyle(this.zoomFactor() > 1 ? CursorStyle.HAND : CursorStyle.NONE);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        return this.zoomFactor() > 1 && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT;
    }

    @Override
    public boolean onMouseDrag(MouseButtonEvent click, double deltaX, double deltaY) {
        if (this.zoomFactor() <= 1 || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.onMouseDrag(click, deltaX, deltaY);
        }
        int cropWidth = this.cropWidth();
        int cropHeight = this.cropHeight();
        this.setPan(
                this.panX - (int) Math.round(deltaX * cropWidth / Math.max(1, this.width)),
                this.panY - (int) Math.round(deltaY * cropHeight / Math.max(1, this.height))
        );
        return true;
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        int cropWidth = this.cropWidth();
        int cropHeight = this.cropHeight();
        this.setPan(this.panX, this.panY);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                this.textureId,
                this.x,
                this.y,
                this.panX,
                this.panY,
                this.width,
                this.height,
                cropWidth,
                cropHeight,
                this.imageWidth,
                this.imageHeight
        );
    }

    private void setPan(int nextX, int nextY) {
        int cropWidth = this.cropWidth();
        int cropHeight = this.cropHeight();
        int clampedX = Math.max(0, Math.min(nextX, this.imageWidth - cropWidth));
        int clampedY = Math.max(0, Math.min(nextY, this.imageHeight - cropHeight));
        if (clampedX == this.panX && clampedY == this.panY) {
            return;
        }
        this.panX = clampedX;
        this.panY = clampedY;
        if (this.panChanged != null) {
            this.panChanged.accept(this.panX, this.panY);
        }
    }

    private int cropWidth() {
        return Math.max(1, this.imageWidth / this.zoomFactor());
    }

    private int cropHeight() {
        return Math.max(1, this.imageHeight / this.zoomFactor());
    }

    private int zoomFactor() {
        return this.zoomStep + 1;
    }
}
