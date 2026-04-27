package io.github.luma.ui.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.Shapes;

public final class CompareOverlayRenderer {

    private static final String CURRENT_WORLD_REFERENCE = "current";
    private static final int MAX_RENDERED_BLOCKS = 2048;
    private static final float FILL_ALPHA = 48.0F;
    private static final float OUTLINE_ALPHA = 0.9F;
    private static final float INSET = 0.002F;
    private static final CompareOverlaySurfaceResolver SURFACE_RESOLVER = new CompareOverlaySurfaceResolver();
    private static final AtomicReference<OverlayState> ACTIVE_STATE = new AtomicReference<>(null);
    private static final AtomicBoolean XRAY_ENABLED = new AtomicBoolean(false);

    private CompareOverlayRenderer() {
    }

    public static void show(String leftVersionId, String rightVersionId, List<DiffBlockEntry> changedBlocks, boolean debugEnabled) {
        show("", leftVersionId, rightVersionId, changedBlocks, debugEnabled);
    }

    public static void show(
            String projectName,
            String leftVersionId,
            String rightVersionId,
            List<DiffBlockEntry> changedBlocks,
            boolean debugEnabled
    ) {
        boolean resolvedDebug = debugEnabled || LumaDebugLog.globalEnabled();
        List<DiffBlockEntry> resolvedBlocks = changedBlocks == null ? List.of() : List.copyOf(changedBlocks);
        ACTIVE_STATE.set(new OverlayState(projectName, leftVersionId, rightVersionId, resolvedBlocks, resolvedDebug, true));
        if (debugEnabled || LumaDebugLog.globalEnabled()) {
            LumaDebugLog.log(
                    "compare-overlay",
                    "Activated compare overlay {} -> {} with {} changed blocks",
                    leftVersionId,
                    rightVersionId,
                    resolvedBlocks.size()
            );
        }
    }

    public static void clear() {
        OverlayState state = ACTIVE_STATE.get();
        if (state != null && (state.debugEnabled() || LumaDebugLog.globalEnabled())) {
            LumaDebugLog.log(
                    "compare-overlay",
                    "Cleared compare overlay {} -> {}",
                    state.leftVersionId(),
                    state.rightVersionId()
            );
        }
        ACTIVE_STATE.set(null);
    }

    public static boolean active() {
        OverlayState state = ACTIVE_STATE.get();
        return state != null && state.visible();
    }

    public static boolean hasData() {
        return ACTIVE_STATE.get() != null;
    }

    public static boolean visible() {
        return active();
    }

    public static RefreshRequest refreshRequest() {
        OverlayState state = ACTIVE_STATE.get();
        return state == null
                ? null
                : new RefreshRequest(
                        state.projectName(),
                        state.leftVersionId(),
                        state.rightVersionId(),
                        state.debugEnabled(),
                        state.visible()
                );
    }

    public static void refresh(
            String projectName,
            String leftVersionId,
            String rightVersionId,
            List<DiffBlockEntry> changedBlocks,
            boolean debugEnabled
    ) {
        OverlayState current = ACTIVE_STATE.get();
        if (current == null) {
            show(projectName, leftVersionId, rightVersionId, changedBlocks, debugEnabled);
            return;
        }

        boolean resolvedDebug = debugEnabled || current.debugEnabled() || LumaDebugLog.globalEnabled();
        ACTIVE_STATE.set(new OverlayState(
                projectName,
                leftVersionId,
                rightVersionId,
                changedBlocks == null ? List.of() : List.copyOf(changedBlocks),
                resolvedDebug,
                current.visible()
        ));
    }

    public static boolean toggleVisibility() {
        while (true) {
            OverlayState state = ACTIVE_STATE.get();
            if (state == null) {
                return false;
            }

            OverlayState replacement = state.withVisible(!state.visible());
            if (!ACTIVE_STATE.compareAndSet(state, replacement)) {
                continue;
            }

            if (replacement.debugEnabled() || LumaDebugLog.globalEnabled()) {
                LumaDebugLog.log(
                        "compare-overlay",
                        "{} compare overlay {} -> {}",
                        replacement.visible() ? "Showed" : "Hid",
                        replacement.leftVersionId(),
                        replacement.rightVersionId()
                );
            }
            return replacement.visible();
        }
    }

    public static void setXrayEnabled(boolean enabled) {
        boolean changed = XRAY_ENABLED.getAndSet(enabled) != enabled;
        if (!changed) {
            return;
        }

        OverlayState state = ACTIVE_STATE.get();
        if (state != null && (state.debugEnabled() || LumaDebugLog.globalEnabled())) {
            LumaDebugLog.log(
                    "compare-overlay",
                    "{} compare overlay x-ray {} -> {}",
                    enabled ? "Enabled" : "Disabled",
                    state.leftVersionId(),
                    state.rightVersionId()
            );
        }
    }

    static boolean xrayEnabled() {
        return XRAY_ENABLED.get();
    }

    static int changedBlockCount() {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.changedBlocks().size();
    }

    public static void render(WorldRenderContext context) {
        OverlayState state = ACTIVE_STATE.get();
        if (state == null || !state.visible() || state.changedBlocks().isEmpty()) {
            return;
        }
        try {
            renderOverlay(context, state);
        } catch (IllegalStateException exception) {
            ACTIVE_STATE.compareAndSet(state, state.withVisible(false));
            LumaMod.LOGGER.warn("Disabled compare overlay after a render pipeline failure", exception);
        }
    }

    private static void renderOverlay(WorldRenderContext context, OverlayState state) {
        boolean xrayEnabled = XRAY_ENABLED.get();
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        PoseStack matrices = context.matrices();
        VertexConsumer fillConsumer = context.consumers().getBuffer(CompareOverlayRenderTypes.fill(xrayEnabled));
        VertexConsumer lineConsumer = context.consumers().getBuffer(CompareOverlayRenderTypes.outline(xrayEnabled));
        for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : state.visibleSurfaceBlocks(camera.x, camera.y, camera.z)) {
            DiffBlockEntry entry = surfaceBlock.entry();
            ColorChannels color = ColorChannels.of(entry.changeType());
            float minX = (float) (entry.pos().x() - camera.x) + INSET;
            float minY = (float) (entry.pos().y() - camera.y) + INSET;
            float minZ = (float) (entry.pos().z() - camera.z) + INSET;
            float maxX = minX + 1.0F - (INSET * 2.0F);
            float maxY = minY + 1.0F - (INSET * 2.0F);
            float maxZ = minZ + 1.0F - (INSET * 2.0F);

            renderFilledBox(matrices, fillConsumer, minX, minY, minZ, maxX, maxY, maxZ, surfaceBlock, color, FILL_ALPHA);
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
            CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock,
            ColorChannels color,
            float alpha
    ) {
        PoseStack.Pose pose = matrices.last();
        int alphaChannel = Math.round(alpha);

        if (surfaceBlock.northExposed()) {
            addQuad(pose, consumer, color, alphaChannel, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ);
        }
        if (surfaceBlock.southExposed()) {
            addQuad(pose, consumer, color, alphaChannel, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ);
        }
        if (surfaceBlock.westExposed()) {
            addQuad(pose, consumer, color, alphaChannel, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ);
        }
        if (surfaceBlock.eastExposed()) {
            addQuad(pose, consumer, color, alphaChannel, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        }
        if (surfaceBlock.downExposed()) {
            addQuad(pose, consumer, color, alphaChannel, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ);
        }
        if (surfaceBlock.upExposed()) {
            addQuad(pose, consumer, color, alphaChannel, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ);
        }
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

    static List<DiffBlockEntry> selectNearestEntries(
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

    public record RefreshRequest(
            String projectName,
            String leftVersionId,
            String rightVersionId,
            boolean debugEnabled,
            boolean visible
    ) {

        public boolean involvesCurrentWorld() {
            return CURRENT_WORLD_REFERENCE.equals(this.leftVersionId)
                    || CURRENT_WORLD_REFERENCE.equals(this.rightVersionId);
        }
    }

    private static final class OverlayState {

        private final String projectName;
        private final String leftVersionId;
        private final String rightVersionId;
        private final List<DiffBlockEntry> changedBlocks;
        private final Set<Long> changedBlockPositions;
        private final boolean debugEnabled;
        private final boolean visible;
        private int cachedCameraBlockX = Integer.MIN_VALUE;
        private int cachedCameraBlockY = Integer.MIN_VALUE;
        private int cachedCameraBlockZ = Integer.MIN_VALUE;
        private List<CompareOverlaySurfaceResolver.SurfaceBlock> cachedVisibleSurfaceBlocks = List.of();

        private OverlayState(
                String projectName,
                String leftVersionId,
                String rightVersionId,
                List<DiffBlockEntry> changedBlocks,
                boolean debugEnabled,
                boolean visible
        ) {
            this(
                    projectName,
                    leftVersionId,
                    rightVersionId,
                    changedBlocks,
                    SURFACE_RESOLVER.indexPositions(changedBlocks),
                    debugEnabled,
                    visible
            );
        }

        private OverlayState(
                String projectName,
                String leftVersionId,
                String rightVersionId,
                List<DiffBlockEntry> changedBlocks,
                Set<Long> changedBlockPositions,
                boolean debugEnabled,
                boolean visible
        ) {
            this.projectName = projectName == null ? "" : projectName;
            this.leftVersionId = leftVersionId;
            this.rightVersionId = rightVersionId;
            this.changedBlocks = List.copyOf(changedBlocks);
            this.changedBlockPositions = changedBlockPositions;
            this.debugEnabled = debugEnabled;
            this.visible = visible;
        }

        private String projectName() {
            return this.projectName;
        }

        private String leftVersionId() {
            return this.leftVersionId;
        }

        private String rightVersionId() {
            return this.rightVersionId;
        }

        private boolean debugEnabled() {
            return this.debugEnabled;
        }

        private boolean visible() {
            return this.visible;
        }

        private synchronized OverlayState withVisible(boolean nextVisible) {
            OverlayState replacement = new OverlayState(
                    this.projectName,
                    this.leftVersionId,
                    this.rightVersionId,
                    this.changedBlocks,
                    this.changedBlockPositions,
                    this.debugEnabled,
                    nextVisible
            );
            replacement.cachedCameraBlockX = this.cachedCameraBlockX;
            replacement.cachedCameraBlockY = this.cachedCameraBlockY;
            replacement.cachedCameraBlockZ = this.cachedCameraBlockZ;
            replacement.cachedVisibleSurfaceBlocks = this.cachedVisibleSurfaceBlocks;
            return replacement;
        }

        private List<DiffBlockEntry> changedBlocks() {
            return this.changedBlocks;
        }

        private synchronized List<CompareOverlaySurfaceResolver.SurfaceBlock> visibleSurfaceBlocks(
                double cameraX,
                double cameraY,
                double cameraZ
        ) {
            int cameraBlockX = Mth.floor(cameraX);
            int cameraBlockY = Mth.floor(cameraY);
            int cameraBlockZ = Mth.floor(cameraZ);
            if (cameraBlockX == this.cachedCameraBlockX
                    && cameraBlockY == this.cachedCameraBlockY
                    && cameraBlockZ == this.cachedCameraBlockZ) {
                return this.cachedVisibleSurfaceBlocks;
            }

            this.cachedCameraBlockX = cameraBlockX;
            this.cachedCameraBlockY = cameraBlockY;
            this.cachedCameraBlockZ = cameraBlockZ;
            long startedAt = System.nanoTime();
            List<DiffBlockEntry> nearestEntries = selectNearestEntries(this.changedBlocks, cameraX, cameraY, cameraZ);
            this.cachedVisibleSurfaceBlocks = SURFACE_RESOLVER.resolve(nearestEntries, this.changedBlockPositions);
            if (this.debugEnabled) {
                LumaDebugLog.log(
                        "compare-overlay",
                        "Rebuilt visible overlay cache for {} -> {} at {}:{}:{}: total={} selected={} rendered={} in {} us",
                        this.leftVersionId,
                        this.rightVersionId,
                        cameraBlockX,
                        cameraBlockY,
                        cameraBlockZ,
                        this.changedBlocks.size(),
                        nearestEntries.size(),
                        this.cachedVisibleSurfaceBlocks.size(),
                        (System.nanoTime() - startedAt) / 1_000L
                );
            }
            return this.cachedVisibleSurfaceBlocks;
        }
    }
}
