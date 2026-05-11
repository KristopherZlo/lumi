package io.github.luma.ui.overlay;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

final class OverlayMeshBuffer {

    private static final Vector4f DEFAULT_COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f DEFAULT_MODEL_OFFSET = new Vector3f();

    private final String label;
    private GpuBuffer vertexBuffer;
    private GpuBuffer indexBuffer;
    private VertexFormat.Mode mode;
    private VertexFormat.IndexType indexType;
    private int indexCount;

    OverlayMeshBuffer(String label) {
        this.label = label;
    }

    boolean ready() {
        return this.vertexBuffer != null && this.indexCount > 0;
    }

    void upload(MeshData meshData) {
        this.close();
        if (meshData == null) {
            return;
        }
        try (meshData) {
            MeshData.DrawState drawState = meshData.drawState();
            this.mode = drawState.mode();
            this.indexType = drawState.indexType();
            this.indexCount = drawState.indexCount();
            this.vertexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Lumi overlay " + this.label + " vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    meshData.vertexBuffer()
            );
            if (meshData.indexBuffer() != null) {
                this.indexBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "Lumi overlay " + this.label + " indices",
                        GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                        meshData.indexBuffer()
                );
            }
        }
    }

    OverlayDrawItem drawItem() {
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(),
                DEFAULT_COLOR_MODULATOR,
                DEFAULT_MODEL_OFFSET,
                new Matrix4f()
        );
        return new OverlayDrawItem(this, transforms);
    }

    void draw(RenderPass renderPass, GpuBufferSlice transforms) {
        renderPass.setUniform("DynamicTransforms", transforms);
        renderPass.setVertexBuffer(0, this.vertexBuffer);
        if (this.indexBuffer == null) {
            RenderSystem.AutoStorageIndexBuffer sequentialBuffer = RenderSystem.getSequentialBuffer(this.mode);
            renderPass.setIndexBuffer(sequentialBuffer.getBuffer(this.indexCount), sequentialBuffer.type());
        } else {
            renderPass.setIndexBuffer(this.indexBuffer, this.indexType);
        }
        renderPass.drawIndexed(0, 0, this.indexCount, 1);
    }

    void close() {
        if (this.vertexBuffer != null) {
            this.vertexBuffer.close();
            this.vertexBuffer = null;
        }
        if (this.indexBuffer != null) {
            this.indexBuffer.close();
            this.indexBuffer = null;
        }
        this.indexCount = 0;
        this.mode = null;
        this.indexType = null;
    }
}

record OverlayDrawItem(OverlayMeshBuffer mesh, GpuBufferSlice transforms) {

    boolean draw(RenderPass renderPass) {
        if (!this.mesh.ready()) {
            return false;
        }
        this.mesh.draw(renderPass, this.transforms);
        return true;
    }
}
