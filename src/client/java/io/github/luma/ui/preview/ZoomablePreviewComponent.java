package io.github.luma.ui.preview;

import java.util.function.BiConsumer;

import org.lwjgl.glfw.GLFW;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class ZoomablePreviewComponent extends BaseUIComponent {

    private final Identifier textureId;
    private final int imageWidth;
    private final int imageHeight;
    private final int zoomStep;
    private final BiConsumer<Integer, Integer> panChanged;
    private int panX;
    private int panY;
    private boolean dragging;
    private int lastMouseX;
    private int lastMouseY;

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
        if (!this.dragging) {
            return;
        }
        if (GLFW.glfwGetMouseButton(
                Minecraft.getInstance().getWindow().handle(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT
        ) != GLFW.GLFW_PRESS) {
            this.dragging = false;
            return;
        }
        this.dragBy(mouseX - this.lastMouseX, mouseY - this.lastMouseY);
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        if (this.zoomFactor() <= 1 || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        this.dragging = true;
        this.lastMouseX = this.x + (int) Math.round(click.x());
        this.lastMouseY = this.y + (int) Math.round(click.y());
        return true;
    }

    @Override
    public boolean onMouseUp(MouseButtonEvent click) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            this.dragging = false;
        }
        return super.onMouseUp(click);
    }

    @Override
    public boolean onMouseDrag(MouseButtonEvent click, double deltaX, double deltaY) {
        if (this.zoomFactor() <= 1 || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.onMouseDrag(click, deltaX, deltaY);
        }
        return true;
    }

    private void dragBy(int deltaX, int deltaY) {
        int cropWidth = this.cropWidth();
        int cropHeight = this.cropHeight();
        this.setPan(
                this.panX - (int) Math.round(deltaX * cropWidth / (double) Math.max(1, this.width)),
                this.panY - (int) Math.round(deltaY * cropHeight / (double) Math.max(1, this.height))
        );
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
