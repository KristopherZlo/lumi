package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Draws the shared missing or animated loading preview placeholder. */
final class LumiPreviewRenderer {
    private static final int FRAME_COUNT = 8;
    private static final int FRAME_MILLIS = 100;
    private static final int LOADING_SIZE = 16;
    private static final int ICON_SIZE = 12;
    private static final int ICON_TEXTURE_SIZE = 24;
    private static final Identifier NO_PREVIEW = texture("new-icons/image.png");
    private static final Identifier[] LOADING = java.util.stream.IntStream
            .range(0, FRAME_COUNT)
            .mapToObj(frame -> texture("loading/loading_" + frame + ".png"))
            .toArray(Identifier[]::new);

    private LumiPreviewRenderer() { }

    static void drawPlaceholder(
            GuiGraphics graphics, int x, int y, int width, int height,
            boolean loading) {
        int size = loading ? LOADING_SIZE : ICON_SIZE;
        int textureSize = loading ? LOADING_SIZE : ICON_TEXTURE_SIZE;
        Identifier texture = loading
                ? LOADING[frame(System.currentTimeMillis())]
                : NO_PREVIEW;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, texture,
                x + (width - size) / 2, y + (height - size) / 2,
                0, 0, size, size,
                textureSize, textureSize, textureSize, textureSize);
    }

    static int frame(long millis) {
        return Math.floorMod(millis / FRAME_MILLIS, FRAME_COUNT);
    }

    private static Identifier texture(String path) {
        return Identifier.fromNamespaceAndPath(
                LumiMod.MOD_ID, "textures/gui/" + path);
    }
}
