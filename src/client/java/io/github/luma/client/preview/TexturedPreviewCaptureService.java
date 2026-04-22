package io.github.luma.client.preview;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import io.github.luma.domain.model.Bounds3i;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import org.joml.Matrix4fStack;

final class TexturedPreviewCaptureService implements AutoCloseable {

    private final PreviewFramingCalculator framingCalculator = new PreviewFramingCalculator();
    private final CachedOrthoProjectionMatrixBuffer projectionMatrixBuffer = new CachedOrthoProjectionMatrixBuffer(
            "Lumi Preview",
            -1000.0F,
            1000.0F,
            true
    );

    PendingPreviewCapture capture(Minecraft client, Bounds3i bounds, PreviewRenderMesh mesh) {
        PreviewFramingCalculator.PreviewFraming framing = this.framingCalculator.calculate(bounds);
        TextureTarget renderTarget = new TextureTarget("Lumi Preview", framing.resolution(), framing.resolution(), true);

        GpuTexture colorTexture = Objects.requireNonNull(renderTarget.getColorTexture(), "Preview color texture is missing");
        GpuTextureView colorTextureView = Objects.requireNonNull(renderTarget.getColorTextureView(), "Preview color texture view is missing");
        GpuTexture depthTexture = Objects.requireNonNull(renderTarget.getDepthTexture(), "Preview depth texture is missing");
        GpuTextureView depthTextureView = Objects.requireNonNull(renderTarget.getDepthTextureView(), "Preview depth texture view is missing");

        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(colorTexture, 0, depthTexture, 1.0);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(this.projectionMatrixBuffer.getBuffer(framing.resolution(), framing.resolution()), ProjectionType.ORTHOGRAPHIC);

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.identity();

        GpuTextureView previousColor = RenderSystem.outputColorTextureOverride;
        GpuTextureView previousDepth = RenderSystem.outputDepthTextureOverride;
        GpuBufferSlice previousLights = RenderSystem.getShaderLights();
        RenderSystem.outputColorTextureOverride = colorTextureView;
        RenderSystem.outputDepthTextureOverride = depthTextureView;

        try {
            client.gameRenderer.lightTexture().updateLightTexture(1.0F);
            client.gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);

            float halfResolution = framing.resolution() * 0.5F;
            float pixelScale = framing.scale() * halfResolution;
            modelViewStack.translate(
                    halfResolution + framing.offsetX() * halfResolution,
                    halfResolution - framing.offsetY() * halfResolution,
                    0.0F
            );
            modelViewStack.scale(pixelScale, pixelScale, pixelScale);
            modelViewStack.rotateX(PreviewFramingCalculator.ISO_PITCH_RADIANS);
            modelViewStack.rotateY(PreviewFramingCalculator.ISO_YAW_RADIANS);
            modelViewStack.translate(-framing.halfX(), -framing.halfY(), -framing.halfZ());
            mesh.render();
        } finally {
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
            if (previousLights != null) {
                RenderSystem.setShaderLights(previousLights);
            }
            modelViewStack.popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }

        return new PendingPreviewCapture(
                renderTarget,
                this.readPixels(renderTarget),
                framing.resolution(),
                framing.resolution()
        );
    }

    private CompletableFuture<NativeImage> readPixels(RenderTarget renderTarget) {
        GpuTexture colorTexture = Objects.requireNonNull(renderTarget.getColorTexture(), "Preview color texture is missing");
        GpuBuffer buffer = RenderSystem.getDevice()
                .createBuffer(() -> "Lumi Preview Readback", 9, (long) renderTarget.width * renderTarget.height * colorTexture.getFormat().pixelSize());
        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        CompletableFuture<NativeImage> future = new CompletableFuture<>();

        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(colorTexture, buffer, 0L, () -> {
            try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(buffer, true, false)) {
                NativeImage image = new NativeImage(renderTarget.width, renderTarget.height, false);
                for (int y = 0; y < renderTarget.height; y++) {
                    for (int x = 0; x < renderTarget.width; x++) {
                        int pixel = mappedView.data().getInt((x + y * renderTarget.width) * colorTexture.getFormat().pixelSize());
                        image.setPixelABGR(x, renderTarget.height - y - 1, pixel);
                    }
                }
                future.complete(image);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            } finally {
                buffer.close();
            }
        }, 0);

        return future;
    }

    @Override
    public void close() {
        this.projectionMatrixBuffer.close();
    }

    record PendingPreviewCapture(
            TextureTarget renderTarget,
            CompletableFuture<NativeImage> imageFuture,
            int width,
            int height
    ) {
    }
}
