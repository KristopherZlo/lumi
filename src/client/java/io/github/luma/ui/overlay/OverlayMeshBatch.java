package io.github.luma.ui.overlay;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;

final class OverlayMeshBatch implements AutoCloseable {

    private static final int SECTION_SIZE = 16;
    private static final int DEFAULT_UPLOAD_BUDGET = 64;
    private static final Vector4f DEFAULT_COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f DEFAULT_MODEL_OFFSET = new Vector3f();

    static final OverlayMeshBatch EMPTY = new OverlayMeshBatch(List.of());

    private final List<SectionMesh> sections;
    private boolean closeRequested;
    private boolean closed;

    private OverlayMeshBatch(List<SectionMesh> sections) {
        this.sections = sections;
    }

    static Builder builder() {
        return new Builder();
    }

    int sectionCount() {
        return this.sections.size();
    }

    RenderStats render(RenderType fillType, RenderType outlineType, Vec3 camera, int renderDistanceChunks) {
        return this.render(fillType, outlineType, camera, renderDistanceChunks, DEFAULT_UPLOAD_BUDGET);
    }

    synchronized RenderStats render(
            RenderType fillType,
            RenderType outlineType,
            Vec3 camera,
            int renderDistanceChunks,
            int uploadBudget
    ) {
        if (this.closed || this.closeRequested || this.sections.isEmpty() || camera == null) {
            return RenderStats.empty(this.sections.size());
        }

        int uploaded = 0;
        int visible = 0;
        int skipped = 0;
        List<SectionMesh> drawableSections = new ArrayList<>();
        for (SectionMesh section : this.sections) {
            if (!section.visibleFrom(camera, renderDistanceChunks)) {
                skipped += 1;
                continue;
            }
            visible += 1;
            if (!section.uploaded() && uploaded < uploadBudget) {
                section.upload(fillType, outlineType);
                uploaded += 1;
            }
            if (section.uploaded()) {
                drawableSections.add(section);
            }
        }

        int filledSections = this.drawPass("fill", fillType, camera, drawableSections, MeshLayer.FILL);
        int outlinedSections = this.drawPass("outline", outlineType, camera, drawableSections, MeshLayer.OUTLINE);
        return new RenderStats(this.sections.size(), visible, skipped, uploaded, filledSections, outlinedSections);
    }

    @Override
    public void close() {
        boolean uploaded;
        synchronized (this) {
            if (this.closed || this.closeRequested) {
                return;
            }
            this.closeRequested = true;
            uploaded = this.hasUploadedBuffers();
        }

        if (uploaded && !RenderSystem.isOnRenderThread()) {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.execute(this::closeOnRenderThread);
                return;
            }
        }
        this.closeOnRenderThread();
    }

    private synchronized void closeOnRenderThread() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (SectionMesh section : this.sections) {
            section.close();
        }
    }

    private boolean hasUploadedBuffers() {
        for (SectionMesh section : this.sections) {
            if (section.hasUploadedBuffers()) {
                return true;
            }
        }
        return false;
    }

    private int drawPass(
            String label,
            RenderType renderType,
            Vec3 camera,
            List<SectionMesh> drawableSections,
            MeshLayer layer
    ) {
        if (drawableSections.isEmpty()) {
            return 0;
        }

        List<DrawItem> drawItems = this.prepareDrawItems(camera, drawableSections, layer);
        if (drawItems.isEmpty()) {
            return 0;
        }

        int drawn = 0;
        RenderTarget renderTarget = Minecraft.getInstance().getMainRenderTarget();
        GpuTextureView colorTexture = RenderSystem.outputColorTextureOverride == null
                ? renderTarget.getColorTextureView()
                : RenderSystem.outputColorTextureOverride;
        GpuTextureView depthTexture = renderTarget.useDepth
                ? (RenderSystem.outputDepthTextureOverride == null
                ? renderTarget.getDepthTextureView()
                : RenderSystem.outputDepthTextureOverride)
                : null;

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Lumi overlay " + label,
                        colorTexture,
                        OptionalInt.empty(),
                        depthTexture,
                        OptionalDouble.empty()
                )) {
            renderPass.setPipeline(renderType.pipeline());
            ScissorState scissorState = RenderSystem.getScissorStateForRenderTypeDraws();
            if (scissorState.enabled()) {
                renderPass.enableScissor(scissorState.x(), scissorState.y(), scissorState.width(), scissorState.height());
            }
            RenderSystem.bindDefaultUniforms(renderPass);
            for (DrawItem item : drawItems) {
                if (item.draw(renderPass)) {
                    drawn += 1;
                }
            }
        }
        return drawn;
    }

    private List<DrawItem> prepareDrawItems(Vec3 camera, List<SectionMesh> drawableSections, MeshLayer layer) {
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        try {
            RenderSystem.getProjectionType().applyLayeringTransform(stack, 1.0F);
            List<DrawItem> drawItems = new ArrayList<>(drawableSections.size());
            for (SectionMesh section : drawableSections) {
                DrawItem item = section.prepareDraw(camera, layer);
                if (item != null) {
                    drawItems.add(item);
                }
            }
            return drawItems;
        } finally {
            stack.popMatrix();
        }
    }

    record RenderStats(
            int totalSections,
            int visibleSections,
            int skippedSections,
            int uploadedSections,
            int filledSections,
            int outlinedSections
    ) {

        private static RenderStats empty(int totalSections) {
            return new RenderStats(totalSections, 0, totalSections, 0, 0, 0);
        }
    }

    static final class Builder {

        private final Map<SectionKey, SectionBuilder> sections = new LinkedHashMap<>();

        void addSurfaceBlock(
                CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock,
                int red,
                int green,
                int blue,
                int fillAlpha,
                int outlineArgb,
                float outlineWidth,
                float fillOutset
        ) {
            var pos = surfaceBlock.entry().pos();
            SectionKey key = SectionKey.fromBlock(pos.x(), pos.y(), pos.z());
            this.add(key, new SurfaceBlockPrimitive(
                    surfaceBlock,
                    red,
                    green,
                    blue,
                    fillAlpha,
                    outlineArgb,
                    outlineWidth,
                    fillOutset,
                    0.0F
            ));
        }

        void addBox(
                int minX,
                int minY,
                int minZ,
                int maxX,
                int maxY,
                int maxZ,
                int red,
                int green,
                int blue,
                int fillAlpha,
                int outlineArgb,
                float outlineWidth,
                float fillOutset,
                float outlineOutset
        ) {
            SectionKey key = SectionKey.fromBlock(minX, minY, minZ);
            this.add(key, new BoxPrimitive(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    red,
                    green,
                    blue,
                    fillAlpha,
                    outlineArgb,
                    outlineWidth,
                    fillOutset,
                    outlineOutset
            ));
        }

        OverlayMeshBatch build() {
            if (this.sections.isEmpty()) {
                return EMPTY;
            }
            List<SectionMesh> meshes = new ArrayList<>(this.sections.size());
            for (SectionBuilder builder : this.sections.values()) {
                meshes.add(builder.build());
            }
            return new OverlayMeshBatch(List.copyOf(meshes));
        }

        private void add(SectionKey key, OverlayPrimitive primitive) {
            this.sections.computeIfAbsent(key, SectionBuilder::new).add(primitive);
        }
    }

    private enum MeshLayer {
        FILL,
        OUTLINE
    }

    private interface OverlayPrimitive {

        OverlayBounds bounds();

        void emitFill(PoseStack matrices, VertexConsumer consumer, int originX, int originY, int originZ);

        void emitOutline(PoseStack matrices, VertexConsumer consumer, int originX, int originY, int originZ);
    }

    private static final class SurfaceBlockPrimitive implements OverlayPrimitive {

        private final CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock;
        private final int red;
        private final int green;
        private final int blue;
        private final int fillAlpha;
        private final int outlineArgb;
        private final float outlineWidth;
        private final float fillOutset;
        private final float outlineOutset;
        private final OverlayBounds bounds;

        private SurfaceBlockPrimitive(
                CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock,
                int red,
                int green,
                int blue,
                int fillAlpha,
                int outlineArgb,
                float outlineWidth,
                float fillOutset,
                float outlineOutset
        ) {
            this.surfaceBlock = surfaceBlock;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.fillAlpha = fillAlpha;
            this.outlineArgb = outlineArgb;
            this.outlineWidth = outlineWidth;
            this.fillOutset = fillOutset;
            this.outlineOutset = outlineOutset;
            var pos = surfaceBlock.entry().pos();
            this.bounds = OverlayBounds.of(
                    pos.x() - fillOutset,
                    pos.y() - fillOutset,
                    pos.z() - fillOutset,
                    pos.x() + 1.0D + fillOutset,
                    pos.y() + 1.0D + fillOutset,
                    pos.z() + 1.0D + fillOutset
            );
        }

        @Override
        public OverlayBounds bounds() {
            return this.bounds;
        }

        @Override
        public void emitFill(PoseStack matrices, VertexConsumer consumer, int originX, int originY, int originZ) {
            var pos = this.surfaceBlock.entry().pos();
            OverlayFaceRenderer.renderFilledBox(
                    matrices,
                    consumer,
                    pos.x() - originX - this.fillOutset,
                    pos.y() - originY - this.fillOutset,
                    pos.z() - originZ - this.fillOutset,
                    pos.x() + 1.0F - originX + this.fillOutset,
                    pos.y() + 1.0F - originY + this.fillOutset,
                    pos.z() + 1.0F - originZ + this.fillOutset,
                    this.surfaceBlock,
                    this.red,
                    this.green,
                    this.blue,
                    this.fillAlpha
            );
        }

        @Override
        public void emitOutline(PoseStack matrices, VertexConsumer consumer, int originX, int originY, int originZ) {
            var pos = this.surfaceBlock.entry().pos();
            renderOutline(
                    matrices,
                    consumer,
                    pos.x() - originX - this.outlineOutset,
                    pos.y() - originY - this.outlineOutset,
                    pos.z() - originZ - this.outlineOutset,
                    1.0D + (this.outlineOutset * 2.0D),
                    1.0D + (this.outlineOutset * 2.0D),
                    1.0D + (this.outlineOutset * 2.0D),
                    this.outlineArgb,
                    this.outlineWidth
            );
        }
    }

    private static final class BoxPrimitive implements OverlayPrimitive {

        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;
        private final int red;
        private final int green;
        private final int blue;
        private final int fillAlpha;
        private final int outlineArgb;
        private final float outlineWidth;
        private final float fillOutset;
        private final float outlineOutset;
        private final OverlayBounds bounds;

        private BoxPrimitive(
                int minX,
                int minY,
                int minZ,
                int maxX,
                int maxY,
                int maxZ,
                int red,
                int green,
                int blue,
                int fillAlpha,
                int outlineArgb,
                float outlineWidth,
                float fillOutset,
                float outlineOutset
        ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.fillAlpha = fillAlpha;
            this.outlineArgb = outlineArgb;
            this.outlineWidth = outlineWidth;
            this.fillOutset = fillOutset;
            this.outlineOutset = outlineOutset;
            this.bounds = OverlayBounds.of(
                    minX - fillOutset,
                    minY - fillOutset,
                    minZ - fillOutset,
                    maxX + fillOutset,
                    maxY + fillOutset,
                    maxZ + fillOutset
            );
        }

        @Override
        public OverlayBounds bounds() {
            return this.bounds;
        }

        @Override
        public void emitFill(PoseStack matrices, VertexConsumer consumer, int originX, int originY, int originZ) {
            OverlayFaceRenderer.renderSolidBox(
                    matrices,
                    consumer,
                    this.minX - originX - this.fillOutset,
                    this.minY - originY - this.fillOutset,
                    this.minZ - originZ - this.fillOutset,
                    this.maxX - originX + this.fillOutset,
                    this.maxY - originY + this.fillOutset,
                    this.maxZ - originZ + this.fillOutset,
                    this.red,
                    this.green,
                    this.blue,
                    this.fillAlpha
            );
        }

        @Override
        public void emitOutline(PoseStack matrices, VertexConsumer consumer, int originX, int originY, int originZ) {
            double width = (this.maxX - this.minX) + (this.outlineOutset * 2.0D);
            double height = (this.maxY - this.minY) + (this.outlineOutset * 2.0D);
            double depth = (this.maxZ - this.minZ) + (this.outlineOutset * 2.0D);
            renderOutline(
                    matrices,
                    consumer,
                    this.minX - originX - this.outlineOutset,
                    this.minY - originY - this.outlineOutset,
                    this.minZ - originZ - this.outlineOutset,
                    width,
                    height,
                    depth,
                    this.outlineArgb,
                    this.outlineWidth
            );
        }
    }

    private static void renderOutline(
            PoseStack matrices,
            VertexConsumer consumer,
            double minX,
            double minY,
            double minZ,
            double width,
            double height,
            double depth,
            int argb,
            float widthPixels
    ) {
        ShapeRenderer.renderShape(
                matrices,
                consumer,
                Shapes.create(new AABB(0.0D, 0.0D, 0.0D, width, height, depth)),
                minX,
                minY,
                minZ,
                argb,
                widthPixels
        );
    }

    private static final class SectionBuilder {

        private final SectionKey key;
        private final List<OverlayPrimitive> primitives = new ArrayList<>();
        private OverlayBounds bounds;

        private SectionBuilder(SectionKey key) {
            this.key = key;
        }

        private void add(OverlayPrimitive primitive) {
            this.primitives.add(primitive);
            this.bounds = this.bounds == null ? primitive.bounds() : this.bounds.union(primitive.bounds());
        }

        private SectionMesh build() {
            return new SectionMesh(this.key, this.bounds, List.copyOf(this.primitives));
        }
    }

    private static final class SectionMesh {

        private final SectionKey key;
        private final OverlayBounds bounds;
        private final List<OverlayPrimitive> primitives;
        private final MeshBuffer fillMesh = new MeshBuffer("fill");
        private final MeshBuffer outlineMesh = new MeshBuffer("outline");
        private boolean uploaded;

        private SectionMesh(SectionKey key, OverlayBounds bounds, List<OverlayPrimitive> primitives) {
            this.key = key;
            this.bounds = bounds;
            this.primitives = primitives;
        }

        private boolean visibleFrom(Vec3 camera, int renderDistanceChunks) {
            int range = Math.max(1, renderDistanceChunks) + 1;
            int cameraChunkX = Math.floorDiv((int) Math.floor(camera.x), SECTION_SIZE);
            int cameraChunkZ = Math.floorDiv((int) Math.floor(camera.z), SECTION_SIZE);
            return this.bounds.intersectsChunkRange(cameraChunkX, cameraChunkZ, range);
        }

        private boolean uploaded() {
            return this.uploaded;
        }

        private boolean hasUploadedBuffers() {
            return this.fillMesh.ready() || this.outlineMesh.ready();
        }

        private void upload(RenderType fillType, RenderType outlineType) {
            if (this.uploaded) {
                return;
            }

            PoseStack matrices = new PoseStack();
            this.fillMesh.upload(this.buildMesh(fillType, matrices, MeshLayer.FILL));
            this.outlineMesh.upload(this.buildMesh(outlineType, matrices, MeshLayer.OUTLINE));
            this.uploaded = true;
        }

        private MeshData buildMesh(RenderType renderType, PoseStack matrices, MeshLayer layer) {
            BufferBuilder buffer = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
            VertexConsumer consumer = buffer;
            int originX = this.key.originX();
            int originY = this.key.originY();
            int originZ = this.key.originZ();
            for (OverlayPrimitive primitive : this.primitives) {
                if (layer == MeshLayer.FILL) {
                    primitive.emitFill(matrices, consumer, originX, originY, originZ);
                } else {
                    primitive.emitOutline(matrices, consumer, originX, originY, originZ);
                }
            }
            return buffer.build();
        }

        private DrawItem prepareDraw(Vec3 camera, MeshLayer layer) {
            MeshBuffer mesh = layer == MeshLayer.FILL ? this.fillMesh : this.outlineMesh;
            if (!mesh.ready()) {
                return null;
            }

            Matrix4fStack stack = RenderSystem.getModelViewStack();
            stack.pushMatrix();
            try {
                stack.translate(
                        (float) (this.key.originX() - camera.x),
                        (float) (this.key.originY() - camera.y),
                        (float) (this.key.originZ() - camera.z)
                );
                return mesh.drawItem();
            } finally {
                stack.popMatrix();
            }
        }

        private void close() {
            this.fillMesh.close();
            this.outlineMesh.close();
            this.uploaded = false;
        }
    }

    private static final class MeshBuffer {

        private final String label;
        private GpuBuffer vertexBuffer;
        private GpuBuffer indexBuffer;
        private VertexFormat.Mode mode;
        private VertexFormat.IndexType indexType;
        private int indexCount;

        private MeshBuffer(String label) {
            this.label = label;
        }

        private boolean ready() {
            return this.vertexBuffer != null && this.indexCount > 0;
        }

        private void upload(MeshData meshData) {
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

        private DrawItem drawItem() {
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                    RenderSystem.getModelViewMatrix(),
                    DEFAULT_COLOR_MODULATOR,
                    DEFAULT_MODEL_OFFSET,
                    new Matrix4f()
            );
            return new DrawItem(this, transforms);
        }

        private void draw(RenderPass renderPass, GpuBufferSlice transforms) {
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

        private void close() {
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

    private record DrawItem(MeshBuffer mesh, GpuBufferSlice transforms) {

        private boolean draw(RenderPass renderPass) {
            if (!this.mesh.ready()) {
                return false;
            }
            this.mesh.draw(renderPass, this.transforms);
            return true;
        }
    }

    private record SectionKey(int sectionX, int sectionY, int sectionZ) {

        private static SectionKey fromBlock(int x, int y, int z) {
            return new SectionKey(
                    Math.floorDiv(x, SECTION_SIZE),
                    Math.floorDiv(y, SECTION_SIZE),
                    Math.floorDiv(z, SECTION_SIZE)
            );
        }

        private int originX() {
            return this.sectionX * SECTION_SIZE;
        }

        private int originY() {
            return this.sectionY * SECTION_SIZE;
        }

        private int originZ() {
            return this.sectionZ * SECTION_SIZE;
        }
    }

    private record OverlayBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

        private static OverlayBounds of(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            return new OverlayBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private OverlayBounds union(OverlayBounds other) {
            return new OverlayBounds(
                    Math.min(this.minX, other.minX),
                    Math.min(this.minY, other.minY),
                    Math.min(this.minZ, other.minZ),
                    Math.max(this.maxX, other.maxX),
                    Math.max(this.maxY, other.maxY),
                    Math.max(this.maxZ, other.maxZ)
            );
        }

        private boolean intersectsChunkRange(int cameraChunkX, int cameraChunkZ, int range) {
            int minChunkX = Math.floorDiv((int) Math.floor(this.minX), SECTION_SIZE);
            int maxChunkX = Math.floorDiv((int) Math.floor(this.maxX - 0.0001D), SECTION_SIZE);
            int minChunkZ = Math.floorDiv((int) Math.floor(this.minZ), SECTION_SIZE);
            int maxChunkZ = Math.floorDiv((int) Math.floor(this.maxZ - 0.0001D), SECTION_SIZE);
            return maxChunkX >= cameraChunkX - range
                    && minChunkX <= cameraChunkX + range
                    && maxChunkZ >= cameraChunkZ - range
                    && minChunkZ <= cameraChunkZ + range;
        }
    }
}
