package io.github.lumi.client.preview;

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
import io.github.lumi.domain.model.BlockBox;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import org.joml.Matrix4fStack;

/** Renders a prepared world mesh into one transparent offscreen PNG source image. */
final class TexturedPreviewCaptureService implements AutoCloseable {
    private final PreviewFramingCalculator framingCalculator =
            new PreviewFramingCalculator();
    private final PreviewImageCropper imageCropper = new PreviewImageCropper();
    private final CachedOrthoProjectionMatrixBuffer projectionMatrixBuffer =
            new CachedOrthoProjectionMatrixBuffer(
                    "Lumi Preview", -1000.0F, 1000.0F, false);

    PendingPreviewCapture capture(
            Minecraft client,
            BlockBox bounds,
            PreviewRenderMesh mesh,
            Executor worker) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(worker, "worker");
        PreviewFramingCalculator.PreviewFraming framing =
                framingCalculator.calculate(bounds);
        TextureTarget target = new TextureTarget(
                "Lumi Preview", framing.resolution(), framing.resolution(), true);
        boolean handedOff = false;
        try {
            render(client, target, framing, mesh);
            PendingPreviewCapture capture = new PendingPreviewCapture(
                    target, readPixels(target).thenApplyAsync(imageCropper::crop, worker));
            handedOff = true;
            return capture;
        } finally {
            if (!handedOff) target.destroyBuffers();
        }
    }

    private void render(
            Minecraft client,
            TextureTarget target,
            PreviewFramingCalculator.PreviewFraming framing,
            PreviewRenderMesh mesh) {
        GpuTexture color = Objects.requireNonNull(
                target.getColorTexture(), "Preview color texture is missing");
        GpuTextureView colorView = Objects.requireNonNull(
                target.getColorTextureView(), "Preview color view is missing");
        GpuTexture depth = Objects.requireNonNull(
                target.getDepthTexture(), "Preview depth texture is missing");
        GpuTextureView depthView = Objects.requireNonNull(
                target.getDepthTextureView(), "Preview depth view is missing");
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(color, 0, depth, 1.0);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(
                projectionMatrixBuffer.getBuffer(
                        framing.resolution(), framing.resolution()),
                ProjectionType.ORTHOGRAPHIC);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        GpuTextureView previousColor = RenderSystem.outputColorTextureOverride;
        GpuTextureView previousDepth = RenderSystem.outputDepthTextureOverride;
        GpuBufferSlice previousLights = RenderSystem.getShaderLights();
        RenderSystem.outputColorTextureOverride = colorView;
        RenderSystem.outputDepthTextureOverride = depthView;
        try {
            client.gameRenderer.lightTexture().updateLightTexture(1.0F);
            client.gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);
            float halfResolution = framing.resolution() * 0.5F;
            float pixelScale = framing.scale() * halfResolution;
            modelView.translate(
                    halfResolution + framing.offsetX() * halfResolution,
                    halfResolution + framing.offsetY() * halfResolution,
                    0.0F);
            modelView.scale(pixelScale, pixelScale, pixelScale);
            modelView.rotateX(PreviewFramingCalculator.ISO_PITCH_RADIANS);
            modelView.rotateY(PreviewFramingCalculator.ISO_YAW_RADIANS);
            modelView.translate(
                    -framing.halfX(), -framing.halfY(), -framing.halfZ());
            mesh.render();
        } finally {
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
            if (previousLights != null) RenderSystem.setShaderLights(previousLights);
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private CompletableFuture<NativeImage> readPixels(RenderTarget target) {
        GpuTexture color = Objects.requireNonNull(
                target.getColorTexture(), "Preview color texture is missing");
        long byteCount = Math.multiplyExact(
                (long) target.width * target.height,
                color.getFormat().pixelSize());
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> "Lumi Preview Readback", 9, byteCount);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        CompletableFuture<NativeImage> future = new CompletableFuture<>();
        encoder.copyTextureToBuffer(color, buffer, 0L, () -> {
            try (GpuBuffer.MappedView mapped = encoder.mapBuffer(buffer, true, false)) {
                NativeImage image = new NativeImage(target.width, target.height, false);
                int pixelSize = color.getFormat().pixelSize();
                for (int y = 0; y < target.height; y++) {
                    for (int x = 0; x < target.width; x++) {
                        int pixel = mapped.data().getInt(
                                (x + y * target.width) * pixelSize);
                        image.setPixelABGR(x, target.height - y - 1, pixel);
                    }
                }
                future.complete(image);
            } catch (Throwable failed) {
                future.completeExceptionally(failed);
            } finally {
                buffer.close();
            }
        }, 0);
        return future;
    }

    @Override
    public void close() {
        projectionMatrixBuffer.close();
    }

    record PendingPreviewCapture(
            TextureTarget renderTarget,
            CompletableFuture<NativeImage> imageFuture) {
        PendingPreviewCapture {
            Objects.requireNonNull(renderTarget, "renderTarget");
            Objects.requireNonNull(imageFuture, "imageFuture");
        }
    }
}
