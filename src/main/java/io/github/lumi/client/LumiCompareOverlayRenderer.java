package io.github.lumi.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.client.state.HighlightPreparationProgress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Renders exact directional Compare blocks without reading client world state. */
final class LumiCompareOverlayRenderer implements AutoCloseable {
    private static final double MAX_DISTANCE_SQUARED = 256.0 * 256.0;
    private static final int NORMAL_ALPHA = 48;
    private static final int XRAY_ALPHA = 96;
    private static final long MAX_GPU_BYTES = 128L * 1024 * 1024;
    private final long residentByteLimit;
    private final Map<CompareOverlayGeometry.Section, CompareOverlayGpuMesh> meshes =
            new IdentityHashMap<>();
    private final CompareOverlayGeometry geometry = new CompareOverlayGeometry();
    private List<BlockChange> source = List.of();
    private List<CompareOverlayGeometry.Section> sections = List.of();
    private CompletableFuture<List<CompareOverlayGeometry.Section>> preparation;
    private AtomicBoolean cancelled = new AtomicBoolean();
    private Frustum frustum;
    private long gpuBytes;
    private boolean ready;
    private boolean failed;
    private boolean presented;
    private AtomicLong preparedWork = new AtomicLong();
    private long visibleBlocks;
    private long uploadedBlocks;

    HighlightPreparationProgress progress() {
        if (source.isEmpty() || ready || failed) return HighlightPreparationProgress.NONE;
        if (preparation != null) {
            return new HighlightPreparationProgress(preparedWork.get(), 3L * source.size());
        }
        long geometryWork = 2L * source.size();
        return new HighlightPreparationProgress(geometryWork + uploadedBlocks, geometryWork + visibleBlocks);
    }

    void retainInput(List<BlockChange> changes) {
        if (changes == source) return;
        close();
        source = changes;
    }

    LumiCompareOverlayRenderer() {
        this(MAX_GPU_BYTES);
    }

    LumiCompareOverlayRenderer(long residentByteLimit) {
        if (residentByteLimit < 0) throw new IllegalArgumentException("Negative Compare GPU budget");
        this.residentByteLimit = residentByteLimit;
    }

    long cachedBytes() {
        return gpuBytes;
    }

    boolean isReady() {
        return ready;
    }

    void setFrustum(Frustum frustum) {
        this.frustum = frustum;
    }

    void render(
            WorldRenderContext context,
            List<BlockChange> changes,
            boolean xray) {
        retainInput(changes);
        if (source.isEmpty()) return;
        if (sections.isEmpty() && preparation == null && !failed) {
            AtomicBoolean token = cancelled;
            AtomicLong progress = preparedWork;
            preparation = CompletableFuture.supplyAsync(
                    () -> geometry.prepare(changes, token::get, progress::set), Util.backgroundExecutor());
        }
        if (preparation != null && preparation.isDone()) {
            try {
                sections = preparation.join();
            } catch (CompletionException failed) {
                LumiMod.LOGGER.error("Lumi Compare overlay preparation failed", failed.getCause());
                this.failed = true;
            }
            preparation = null;
        }
        if (sections.isEmpty()) return;
        var camera = context.worldState().cameraRenderState.pos;
        var visible = new ArrayList<CompareOverlayGeometry.Section>();
        for (var section : sections) {
            var bounds = section.bounds();
            double x = Math.max(bounds.minX - camera.x, Math.max(0, camera.x - bounds.maxX));
            double y = Math.max(bounds.minY - camera.y, Math.max(0, camera.y - bounds.maxY));
            double z = Math.max(bounds.minZ - camera.z, Math.max(0, camera.z - bounds.maxZ));
            if (distanceSquared(x, y, z) <= MAX_DISTANCE_SQUARED
                    && (frustum == null || frustum.isVisible(bounds))) visible.add(section);
        }
        long uploadStarted = System.nanoTime();
        long missingBytes = visible.stream().filter(section -> !meshes.containsKey(section))
                .mapToLong(CompareOverlayGpuMesh::estimatedBytes).sum();
        if (gpuBytes + missingBytes > residentByteLimit) {
            Set<CompareOverlayGeometry.Section> visibleSet = Collections.newSetFromMap(new IdentityHashMap<>());
            visibleSet.addAll(visible);
            meshes.entrySet().removeIf(entry -> {
                if (visibleSet.contains(entry.getKey())) return false;
                gpuBytes -= entry.getValue().bytes();
                entry.getValue().close();
                return true;
            });
        }
        ready = true;
        for (var section : visible) {
            if (meshes.containsKey(section)
                    || gpuBytes + CompareOverlayGpuMesh.estimatedBytes(section) > residentByteLimit
                    || System.nanoTime() - uploadStarted >= 4_000_000) continue;
            CompareOverlayGpuMesh mesh = new CompareOverlayGpuMesh(section);
            if (gpuBytes + mesh.bytes() > residentByteLimit) {
                mesh.close();
                break;
            }
            meshes.put(section, mesh);
            gpuBytes += mesh.bytes();
        }
        visibleBlocks = 0;
        uploadedBlocks = 0;
        for (var section : visible) {
            visibleBlocks += section.blocks().size();
            if (!meshes.containsKey(section)
                    && gpuBytes + CompareOverlayGpuMesh.estimatedBytes(section) <= residentByteLimit) {
                ready = false;
            } else {
                uploadedBlocks += section.blocks().size();
            }
        }
        if (ready && !visible.isEmpty()) presented = true;
        if (presented) {
            draw(context, visible, xray, false);
            draw(context, visible, xray, true);
        }
    }

    private void draw(WorldRenderContext context, List<CompareOverlayGeometry.Section> visible,
            boolean xray, boolean outline) {
        var retained = new ArrayList<DrawBatch>();
        for (var section : visible) {
            var mesh = meshes.get(section);
            if (mesh != null && mesh.layer(outline) != null) {
                retained.add(new DrawBatch(section, mesh.layer(outline)));
            } else if (mesh == null
                    && gpuBytes + CompareOverlayGpuMesh.estimatedBytes(section) > residentByteLimit) {
                drawBatches(context, retained, xray, outline);
                retained.clear();
                // Preserve draw order while keeping overflow memory bounded to one layer.
                try (var layer = CompareOverlayGpuMesh.uploadLayer(section, outline)) {
                    if (layer != null) drawBatches(context, List.of(new DrawBatch(section, layer)), xray, outline);
                }
            }
        }
        drawBatches(context, retained, xray, outline);
    }

    private void drawBatches(WorldRenderContext context, List<DrawBatch> batches,
            boolean xray, boolean outline) {
        if (batches.isEmpty()) return;
        var type = outline ? LumiCompareRenderTypes.outline(xray) : LumiCompareRenderTypes.fill(xray);
        var target = Minecraft.getInstance().getMainRenderTarget();
        //? if >=26.3 {
        /*var color = target.getColorTextureView();
        var depth = target.getDepthTextureView();
        *///?} else {
        var color = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
        var depth = RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        //?}
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        LayeringTransform.VIEW_OFFSET_Z_LAYERING.getModifier().accept(modelView);
        try {
            var camera = context.worldState().cameraRenderState.pos;
            var tint = new Vector4f(1, 1, 1,
                    outline ? 1 : (xray ? XRAY_ALPHA : NORMAL_ALPHA) / 255.0F);
            var transforms = new DynamicUniforms.Transform[batches.size()];
            var baseTransform = new Matrix4f(RenderSystem.getModelViewMatrix())
                    .mul(context.matrices().last().pose());
            var offset = new Vector3f();
            var texture = new Matrix4f();
            int maxIndices = 0;
            for (int i = 0; i < batches.size(); i++) {
                var batch = batches.get(i);
                var section = batch.section();
                var transform = new Matrix4f(baseTransform).translate((float) (section.x() - camera.x),
                        (float) (section.y() - camera.y), (float) (section.z() - camera.z));
                transforms[i] = new DynamicUniforms.Transform(transform, tint, offset, texture);
                maxIndices = Math.max(maxIndices, batch.layer().indexCount());
            }
            // Grow shared buffers before opening the pass; all its draws must retain valid slices.
            var uniforms = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
            var indices = RenderSystem.getSequentialBuffer(batches.getFirst().layer().mode());
            var indexBuffer = indices.getBuffer(maxIndices);
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            try (var pass = encoder.createRenderPass(
                    () -> "Lumi Compare", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                pass.setPipeline(type.pipeline());
                RenderSystem.bindDefaultUniforms(pass);
                pass.setIndexBuffer(indexBuffer, indices.type());
                for (int i = 0; i < batches.size(); i++) {
                    var layer = batches.get(i).layer();
                    pass.setUniform("DynamicTransforms", uniforms[i]);
                    pass.setVertexBuffer(0, layer.buffer());
                    pass.drawIndexed(0, 0, layer.indexCount(), 1);
                }
            }
            //? if >=26.3
            /*encoder.submit();*/
        } finally {
            modelView.popMatrix();
        }
    }

    private record DrawBatch(CompareOverlayGeometry.Section section, CompareOverlayGpuMesh.Layer layer) { }

    @Override
    public void close() {
        if (source.isEmpty() && preparation == null && meshes.isEmpty()) return;
        cancelled.set(true);
        cancelled = new AtomicBoolean();
        preparation = null;
        source = List.of();
        sections = List.of();
        meshes.values().forEach(CompareOverlayGpuMesh::close);
        meshes.clear();
        gpuBytes = 0;
        ready = false;
        failed = false;
        presented = false;
        preparedWork = new AtomicLong();
        visibleBlocks = 0;
        uploadedBlocks = 0;
    }

    static int color(BlockChange.Kind kind) {
        return switch (kind) {
            case ADDED -> 0xff55ff55;
            case REMOVED -> 0xffff5555;
            case CHANGED -> 0xffffd455;
        };
    }

    private static double distanceSquared(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    static int exposedFaceCount(List<BlockChange> changes) {
        Set<Long> occupied = positions(changes);
        int count = 0;
        for (BlockChange change : changes) {
            for (Direction side : Direction.values()) {
                if (!occupied.contains(BlockPos.asLong(
                        change.x() + side.getStepX(),
                        change.y() + side.getStepY(),
                        change.z() + side.getStepZ()))) count++;
            }
        }
        return count;
    }

    private static Set<Long> positions(List<BlockChange> changes) {
        Set<Long> occupied = new HashSet<>();
        changes.forEach(change -> occupied.add(
                BlockPos.asLong(change.x(), change.y(), change.z())));
        return occupied;
    }

    static void renderSolidBox(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            int red,
            int green,
            int blue,
            int alpha) {
        for (Direction side : Direction.values()) {
            renderFace(pose, consumer, x1, y1, z1, x2, y2, z2,
                    side, red, green, blue, alpha);
        }
    }

    static void renderFace(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            Direction side,
            int red, int green, int blue, int alpha) {
        switch (side) {
            case NORTH -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1);
            case SOUTH -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y1, z2, x1, y2, z2, x2, y2, z2, x2, y1, z2);
            case WEST -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y1, z1, x1, y2, z1, x1, y2, z2, x1, y1, z2);
            case EAST -> quad(pose, consumer, red, green, blue, alpha,
                    x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1);
            case DOWN -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y1, z1, x1, y1, z2, x2, y1, z2, x2, y1, z1);
            case UP -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2);
        }
    }

    private static void quad(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int red,
            int green,
            int blue,
            int alpha,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4) {
        consumer.addVertex(pose, x1, y1, z1).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x2, y2, z2).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x3, y3, z3).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x4, y4, z4).setColor(red, green, blue, alpha);
    }
}
