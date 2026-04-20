package io.github.luma.ui.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.Shapes;

public final class CompareOverlayRenderer {

    private static final int MAX_RENDERED_BLOCKS = 2048;
    private static final float FILL_ALPHA = 72.0F;
    private static final float OUTLINE_ALPHA = 0.9F;
    private static final float INSET = 0.002F;
    private static final AtomicReference<OverlayState> ACTIVE_STATE = new AtomicReference<>(null);

    private CompareOverlayRenderer() {
    }

    public static void show(String leftVersionId, String rightVersionId, List<DiffBlockEntry> changedBlocks) {
        ACTIVE_STATE.set(new OverlayState(leftVersionId, rightVersionId, List.copyOf(changedBlocks)));
    }

    public static void clear() {
        ACTIVE_STATE.set(null);
    }

    public static boolean active() {
        return ACTIVE_STATE.get() != null;
    }

    public static void render(WorldRenderContext context) {
        OverlayState state = ACTIVE_STATE.get();
        if (state == null || state.changedBlocks().isEmpty()) {
            return;
        }

        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        PoseStack matrices = context.matrices();
        VertexConsumer fillConsumer = context.consumers().getBuffer(RenderTypes.debugFilledBox());
        VertexConsumer lineConsumer = context.consumers().getBuffer(RenderTypes.linesTranslucent());
        for (DiffBlockEntry entry : state.visibleEntries(camera.x, camera.y, camera.z)) {
            ColorChannels color = ColorChannels.of(entry.changeType());
            float minX = (float) (entry.pos().x() - camera.x) + INSET;
            float minY = (float) (entry.pos().y() - camera.y) + INSET;
            float minZ = (float) (entry.pos().z() - camera.z) + INSET;
            float maxX = minX + 1.0F - (INSET * 2.0F);
            float maxY = minY + 1.0F - (INSET * 2.0F);
            float maxZ = minZ + 1.0F - (INSET * 2.0F);

            renderFilledBox(matrices, fillConsumer, minX, minY, minZ, maxX, maxY, maxZ, color, FILL_ALPHA);
            ShapeRenderer.renderShape(
                    matrices,
                    lineConsumer,
                    Shapes.block(),
                    entry.pos().x() - camera.x,
                    entry.pos().y() - camera.y,
                    entry.pos().z() - camera.z,
                    color.argb(),
                    OUTLINE_ALPHA
            );
        }
    }

    private static void renderFilledBox(
            PoseStack matrices,
            VertexConsumer consumer,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            ColorChannels color,
            float alpha
    ) {
        PoseStack.Pose pose = matrices.last();
        int alphaChannel = Math.round(alpha);

        addQuad(pose, consumer, color, alphaChannel, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ);
        addQuad(pose, consumer, color, alphaChannel, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ);
        addQuad(pose, consumer, color, alphaChannel, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ);
        addQuad(pose, consumer, color, alphaChannel, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        addQuad(pose, consumer, color, alphaChannel, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ);
        addQuad(pose, consumer, color, alphaChannel, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ);
    }

    private static void addQuad(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            ColorChannels color,
            int alpha,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4
    ) {
        consumer.addVertex(pose, x1, y1, z1).setColor(color.red(), color.green(), color.blue(), alpha);
        consumer.addVertex(pose, x2, y2, z2).setColor(color.red(), color.green(), color.blue(), alpha);
        consumer.addVertex(pose, x3, y3, z3).setColor(color.red(), color.green(), color.blue(), alpha);
        consumer.addVertex(pose, x4, y4, z4).setColor(color.red(), color.green(), color.blue(), alpha);
    }

    private record ColorChannels(int red, int green, int blue, int argb) {

        private static ColorChannels of(ChangeType type) {
            return switch (type) {
                case ADDED -> new ColorChannels(0x55, 0xFF, 0x55, 0xFF55FF55);
                case REMOVED -> new ColorChannels(0xFF, 0x55, 0x55, 0xFFFF5555);
                case CHANGED -> new ColorChannels(0xFF, 0xD4, 0x55, 0xFFFFD455);
            };
        }
    }

    private static List<DiffBlockEntry> selectNearestEntries(
            List<DiffBlockEntry> changedBlocks,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        if (changedBlocks.isEmpty()) {
            return List.of();
        }

        PriorityQueue<RankedEntry> selected = new PriorityQueue<>(
                MAX_RENDERED_BLOCKS,
                Comparator.comparingDouble(RankedEntry::distanceSquared).reversed()
        );
        for (DiffBlockEntry entry : changedBlocks) {
            double distanceSquared = distanceSquared(entry, cameraX, cameraY, cameraZ);
            if (selected.size() < MAX_RENDERED_BLOCKS) {
                selected.add(new RankedEntry(entry, distanceSquared));
                continue;
            }

            RankedEntry farthest = selected.peek();
            if (farthest != null && distanceSquared < farthest.distanceSquared()) {
                selected.poll();
                selected.add(new RankedEntry(entry, distanceSquared));
            }
        }

        List<RankedEntry> ranked = new ArrayList<>(selected);
        ranked.sort(Comparator.comparingDouble(RankedEntry::distanceSquared));
        List<DiffBlockEntry> result = new ArrayList<>(ranked.size());
        for (RankedEntry entry : ranked) {
            result.add(entry.entry());
        }
        return List.copyOf(result);
    }

    private static double distanceSquared(DiffBlockEntry entry, double cameraX, double cameraY, double cameraZ) {
        double dx = (entry.pos().x() + 0.5D) - cameraX;
        double dy = (entry.pos().y() + 0.5D) - cameraY;
        double dz = (entry.pos().z() + 0.5D) - cameraZ;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private record RankedEntry(DiffBlockEntry entry, double distanceSquared) {
    }

    private static final class OverlayState {

        private final String leftVersionId;
        private final String rightVersionId;
        private final List<DiffBlockEntry> changedBlocks;
        private int cachedCameraBlockX = Integer.MIN_VALUE;
        private int cachedCameraBlockY = Integer.MIN_VALUE;
        private int cachedCameraBlockZ = Integer.MIN_VALUE;
        private List<DiffBlockEntry> cachedVisibleEntries = List.of();

        private OverlayState(String leftVersionId, String rightVersionId, List<DiffBlockEntry> changedBlocks) {
            this.leftVersionId = leftVersionId;
            this.rightVersionId = rightVersionId;
            this.changedBlocks = List.copyOf(changedBlocks);
        }

        private List<DiffBlockEntry> changedBlocks() {
            return this.changedBlocks;
        }

        private synchronized List<DiffBlockEntry> visibleEntries(double cameraX, double cameraY, double cameraZ) {
            int cameraBlockX = Mth.floor(cameraX);
            int cameraBlockY = Mth.floor(cameraY);
            int cameraBlockZ = Mth.floor(cameraZ);
            if (cameraBlockX == this.cachedCameraBlockX
                    && cameraBlockY == this.cachedCameraBlockY
                    && cameraBlockZ == this.cachedCameraBlockZ) {
                return this.cachedVisibleEntries;
            }

            this.cachedCameraBlockX = cameraBlockX;
            this.cachedCameraBlockY = cameraBlockY;
            this.cachedCameraBlockZ = cameraBlockZ;
            this.cachedVisibleEntries = selectNearestEntries(this.changedBlocks, cameraX, cameraY, cameraZ);
            return this.cachedVisibleEntries;
        }
    }
}
