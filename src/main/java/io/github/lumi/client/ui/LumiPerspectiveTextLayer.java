package io.github.lumi.client.ui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.lumi.mixin.client.GuiGraphicsAccessor;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Vector2f;

/** Projects a complete text plane toward one centered horizon. */
final class LumiPerspectiveTextLayer {
    static final int LINE_STRIDE = 14;
    private static final float PERSPECTIVE = 0.9F;
    private static final float MIN_SCALE = 0.22F;

    void render(
            GuiGraphics graphics,
            Font font,
            List<FormattedCharSequence> lines,
            int x,
            int y,
            int width,
            int height,
            float scrollPixels) {
        float bottom = y + height + 18.0F;
        float firstY = bottom - scrollPixels;
        float maximumDistance = height * (1.0F / MIN_SCALE - 1.0F)
                / PERSPECTIVE;
        int first = Math.max(0, (int) Math.ceil(
                (scrollPixels - maximumDistance) / LINE_STRIDE));
        int last = Math.min(lines.size(), (int) Math.floor(
                scrollPixels / LINE_STRIDE) + 1);
        float centerX = x + width / 2.0F;
        ScreenRectangle scissor = graphics.scissorStack.peek();
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        var state = ((GuiGraphicsAccessor) graphics).lumi$renderState();
        for (int index = first; index < last; index++) {
            float lineY = firstY + index * LINE_STRIDE;
            float distance = bottom - lineY;
            float scale = projectionScale(distance, height);
            int brightness = 90 + Math.round(scale * 165.0F);
            int color = 0xff000000 | brightness << 16
                    | Math.round(brightness * 0.84F) << 8;
            FormattedCharSequence line = lines.get(index);
            float lineX = centerX - font.width(line) / 2.0F;
            font.prepareText(line, lineX, lineY, color, false, false, 0)
                    .visit(new Font.GlyphVisitor() {
                        @Override
                        public void acceptGlyph(TextRenderable.Styled glyph) {
                            state.submitGuiElement(new PerspectiveGlyph(
                                    pose, glyph, scissor,
                                    centerX, bottom, height));
                        }

                        @Override
                        public void acceptEffect(TextRenderable effect) {
                            state.submitGuiElement(new PerspectiveGlyph(
                                    pose, effect, scissor,
                                    centerX, bottom, height));
                        }
                    });
        }
    }

    static ProjectedPoint project(
            float sourceX,
            float sourceY,
            float centerX,
            float bottom,
            int height) {
        float distance = bottom - sourceY;
        float scale = projectionScale(distance, height);
        return new ProjectedPoint(
                centerX + (sourceX - centerX) * scale,
                bottom - distance * scale);
    }

    static float projectionScale(float distance, int height) {
        float plane = Math.max(1, height);
        return plane / (plane + Math.max(0.0F, distance) * PERSPECTIVE);
    }

    record ProjectedPoint(float x, float y) { }

    private record PerspectiveGlyph(
            Matrix3x2fc pose,
            TextRenderable renderable,
            ScreenRectangle scissorArea,
            float centerX,
            float bottom,
            int height) implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer consumer) {
            renderable.render(
                    new Matrix4f(),
                    new PerspectiveVertexConsumer(
                            consumer, pose, centerX, bottom, height),
                    0x00f000f0,
                    true);
        }

        @Override
        public RenderPipeline pipeline() {
            return renderable.guiPipeline();
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.singleTextureWithLightmap(
                    renderable.textureView(),
                    RenderSystem.getSamplerCache()
                            .getClampToEdge(FilterMode.NEAREST));
        }

        @Override
        public ScreenRectangle bounds() {
            return scissorArea;
        }
    }

    private static final class PerspectiveVertexConsumer
            implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Matrix3x2fc pose;
        private final float centerX;
        private final float bottom;
        private final int height;

        private PerspectiveVertexConsumer(
                VertexConsumer delegate,
                Matrix3x2fc pose,
                float centerX,
                float bottom,
                int height) {
            this.delegate = delegate;
            this.pose = pose;
            this.centerX = centerX;
            this.bottom = bottom;
            this.height = height;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            ProjectedPoint projected = project(
                    x, y, centerX, bottom, height);
            Vector2f transformed = pose.transformPosition(
                    new Vector2f(projected.x(), projected.y()));
            delegate.addVertex(transformed.x, transformed.y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }
    }
}
