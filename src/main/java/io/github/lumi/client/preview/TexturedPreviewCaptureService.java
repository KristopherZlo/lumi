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
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Matrix4fStack;

/** Renders a prepared world mesh into one transparent offscreen PNG source image. */
final class TexturedPreviewCaptureService implements AutoCloseable {
    private final PreviewFramingCalculator framingCalculator =
            new PreviewFramingCalculator();
    private final PreviewImageCropper imageCropper = new PreviewImageCropper();
    private final CachedOrthoProjectionMatrixBuffer projectionMatrixBuffer =
            //? if >=26.1 {
            /*new CachedOrthoProjectionMatrixBuffer("Lumi Preview");
            *///?} else {
            new CachedOrthoProjectionMatrixBuffer(
                    "Lumi Preview", -1000.0F, 1000.0F, false);
            //?}
    private FogRenderer previewFog;

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
            CompletableFuture<NativeImage> image = new CompletableFuture<>();
            readPixels(target).whenComplete((pixels, failed) -> {
                if (failed != null) { image.completeExceptionally(failed); return; }
                try {
                    worker.execute(() -> {
                        try {
                            NativeImage cropped = imageCropper.crop(pixels);
                            if (!image.complete(cropped)) cropped.close();
                        } catch (Throwable error) { image.completeExceptionally(error); }
                    });
                } catch (RuntimeException rejected) {
                    pixels.close();
                    image.completeExceptionally(rejected);
                }
            });
            PendingPreviewCapture capture = new PendingPreviewCapture(
                    target, image);
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
        CommandEncoder clear = RenderSystem.getDevice().createCommandEncoder();
        clear.clearColorAndDepthTextures(color, 0, depth, 1.0);
        //? if >=26.3
        /*clear.submit();*/
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(
                projection(framing.resolution()),
                ProjectionType.ORTHOGRAPHIC);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        //? if <26.3
        GpuTextureView previousColor = RenderSystem.outputColorTextureOverride;
        //? if <26.3
        GpuTextureView previousDepth = RenderSystem.outputDepthTextureOverride;
        GpuBufferSlice previousLights = RenderSystem.getShaderLights();
        GpuBufferSlice previousFog = RenderSystem.getShaderFog();
        //? if <26.3
        RenderSystem.outputColorTextureOverride = colorView;
        //? if <26.3
        RenderSystem.outputDepthTextureOverride = depthView;
        try {
            RenderSystem.setShaderFog(fog().getBuffer(FogRenderer.FogMode.NONE));
            //? if <26.1
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
            //? if >=26.3 {
            /*mesh.render(colorView, depthView);
            *///?} else {
            mesh.render();
            //?}
        } finally {
            //? if <26.3
            RenderSystem.outputColorTextureOverride = previousColor;
            //? if <26.3
            RenderSystem.outputDepthTextureOverride = previousDepth;
            if (previousLights != null) RenderSystem.setShaderLights(previousLights);
            if (previousFog != null) RenderSystem.setShaderFog(previousFog);
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private FogRenderer fog() {
        if (previewFog == null) previewFog = new FogRenderer();
        return previewFog;
    }

    private GpuBufferSlice projection(int resolution) {
        //? if >=26.1 {
        /*var projection = new net.minecraft.client.renderer.Projection();
        projection.setupOrtho(-1000.0F, 1000.0F, resolution, resolution, false);
        return projectionMatrixBuffer.getBuffer(projection);
        *///?} else {
        return projectionMatrixBuffer.getBuffer(resolution, resolution);
        //?}
    }

    private CompletableFuture<NativeImage> readPixels(RenderTarget target) {
        GpuTexture color = Objects.requireNonNull(
                target.getColorTexture(), "Preview color texture is missing");
        long byteCount = Math.multiplyExact(
                (long) target.width * target.height,
                color.getFormat().pixelSize());
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                () -> "Lumi Preview Readback", 9, byteCount);
        int width = target.width;
        int height = target.height;
        int pixelSize = color.getFormat().pixelSize();
        CompletableFuture<NativeImage> future = new CompletableFuture<>();
        future.whenComplete((image, failed) -> buffer.close());
        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(color, buffer, 0L, () -> {
                try {
                    NativeImage image = copyPixels(encoder, buffer, width, height, pixelSize);
                    if (!future.complete(image)) image.close();
                } catch (Throwable failed) { future.completeExceptionally(failed); }
            }, 0);
            //? if >=26.3
            /*encoder.submit();*/
        } catch (RuntimeException | Error failed) { future.completeExceptionally(failed); }
        return future;
    }

    private NativeImage copyPixels(CommandEncoder encoder, GpuBuffer buffer, int width, int height, int pixelSize) {
        NativeImage image = new NativeImage(width, height, false);
        try (GpuBuffer.MappedView mapped = encoder.mapBuffer(buffer, true, false)) {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                image.setPixelABGR(x, height - y - 1, mapped.data().getInt((x + y * width) * pixelSize));
            return image;
        } catch (RuntimeException | Error failed) {
            image.close();
            throw failed;
        }
    }

    @Override
    public void close() {
        projectionMatrixBuffer.close();
        if (previewFog != null) previewFog.close();
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
