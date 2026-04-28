package io.github.luma.ui.overlay;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.Shapes;

public final class CompareOverlayRenderer {

    private static final String CURRENT_WORLD_REFERENCE = "current";
    private static final int MAX_RENDERED_BLOCKS = 2048;
    private static final float FILL_ALPHA = 112.0F;
    private static final float OUTLINE_WIDTH = 2.75F;
    private static final float FACE_OUTSET = 0.003F;
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
        OverlayState state = new OverlayState(projectName, leftVersionId, rightVersionId, resolvedBlocks, resolvedDebug, true);
        ACTIVE_STATE.set(state);
        OverlayDiagnostics.getInstance().logNow(
                resolvedDebug,
                "compare-overlay",
                "Activated compare overlay {} -> {} with changedBlocks={} surfaceBlocks={}",
                leftVersionId,
                rightVersionId,
                resolvedBlocks.size(),
                state.surfaceBlockCount()
        );
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
        OverlayState replacement = new OverlayState(
                projectName,
                leftVersionId,
                rightVersionId,
                changedBlocks == null ? List.of() : List.copyOf(changedBlocks),
                resolvedDebug,
                current.visible()
        );
        ACTIVE_STATE.set(replacement);
        OverlayDiagnostics.getInstance().log(
                resolvedDebug,
                "compare-refresh",
                "compare-overlay",
                "Refreshed compare overlay {} -> {} with changedBlocks={} surfaceBlocks={} visible={}",
                leftVersionId,
                rightVersionId,
                replacement.changedBlocks().size(),
                replacement.surfaceBlockCount(),
                replacement.visible()
        );
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

    static int visibleSurfaceBlockCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.visibleSurfaceBlocks(cameraX, cameraY, cameraZ).size();
    }

    public static void render(WorldRenderContext context) {
        OverlayState state = ACTIVE_STATE.get();
        if (state == null) {
            OverlayDiagnostics.getInstance().log(
                    false,
                    "compare-skip-no-state",
                    "compare-overlay",
                    "Render skipped reason={} changedBlocks={} surfaceBlocks={} visible={} xray={}",
                    "no-state",
                    0,
                    0,
                    false,
                    XRAY_ENABLED.get()
            );
            return;
        }
        if (!state.visible()) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "compare-skip-hidden",
                    "compare-overlay",
                    "Render skipped reason={} changedBlocks={} surfaceBlocks={} visible={} xray={}",
                    "hidden",
                    state.changedBlocks().size(),
                    state.surfaceBlockCount(),
                    false,
                    XRAY_ENABLED.get()
            );
            return;
        }
        if (state.changedBlocks().isEmpty()) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "compare-skip-empty-diff",
                    "compare-overlay",
                    "Render skipped reason={} changedBlocks={} surfaceBlocks={} visible={} xray={}",
                    "empty-diff",
                    0,
                    state.surfaceBlockCount(),
                    true,
                    XRAY_ENABLED.get()
            );
            return;
        }
        try {
            renderOverlay(context, state);
        } catch (RuntimeException exception) {
            OverlayDiagnostics.getInstance().logNow(
                    state.debugEnabled(),
                    "compare-overlay",
                    "Render failed with {}: {}",
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            ACTIVE_STATE.compareAndSet(state, state.withVisible(false));
            LumaMod.LOGGER.warn("Disabled compare overlay after a render pipeline failure", exception);
        }
    }

    private static void renderOverlay(WorldRenderContext context, OverlayState state) {
        boolean xrayEnabled = XRAY_ENABLED.get();
        if (context == null) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "compare-skip-null-context",
                    "compare-overlay",
                    "Render skipped reason={} changedBlocks={} surfaceBlocks={} visible={} xray={}",
                    "null-context",
                    state.changedBlocks().size(),
                    state.surfaceBlockCount(),
                    state.visible(),
                    xrayEnabled
            );
            return;
        }
        var matrices = context.matrices();
        var consumers = context.consumers();
        if (matrices == null) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "compare-skip-null-matrices",
                    "compare-overlay",
                    "Render skipped reason={} changedBlocks={} surfaceBlocks={} visible={} xray={}",
                    "null-matrices",
                    state.changedBlocks().size(),
                    state.surfaceBlockCount(),
                    state.visible(),
                    xrayEnabled
            );
            return;
        }
        if (consumers == null) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "compare-skip-null-consumers",
                    "compare-overlay",
                    "Render skipped reason={} changedBlocks={} surfaceBlocks={} visible={} xray={}",
                    "null-consumers",
                    state.changedBlocks().size(),
                    state.surfaceBlockCount(),
                    state.visible(),
                    xrayEnabled
            );
            return;
        }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        List<CompareOverlaySurfaceResolver.SurfaceBlock> visibleSurfaceBlocks = state.visibleSurfaceBlocks(
                camera.x,
                camera.y,
                camera.z
        );
        OverlayDiagnostics.getInstance().log(
                state.debugEnabled(),
                "compare-frame",
                "compare-overlay",
                "Render frame changedBlocks={} surfaceBlocks={} renderedBlocks={} xray={} camera={}:{}:{}",
                state.changedBlocks().size(),
                state.surfaceBlockCount(),
                visibleSurfaceBlocks.size(),
                xrayEnabled,
                camera.x,
                camera.y,
                camera.z
        );

        VertexConsumer fillConsumer = consumers.getBuffer(CompareOverlayRenderTypes.fill(xrayEnabled));
        for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : visibleSurfaceBlocks) {
            DiffBlockEntry entry = surfaceBlock.entry();
            ColorChannels color = ColorChannels.of(entry.changeType());
            float minX = (float) (entry.pos().x() - camera.x) - FACE_OUTSET;
            float minY = (float) (entry.pos().y() - camera.y) - FACE_OUTSET;
            float minZ = (float) (entry.pos().z() - camera.z) - FACE_OUTSET;
            float maxX = (float) (entry.pos().x() + 1.0D - camera.x) + FACE_OUTSET;
            float maxY = (float) (entry.pos().y() + 1.0D - camera.y) + FACE_OUTSET;
            float maxZ = (float) (entry.pos().z() + 1.0D - camera.z) + FACE_OUTSET;

            OverlayFaceRenderer.renderFilledBox(
                    matrices,
                    fillConsumer,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    surfaceBlock,
                    color.red(),
                    color.green(),
                    color.blue(),
                    Math.round(FILL_ALPHA)
            );
        }

        VertexConsumer lineConsumer = consumers.getBuffer(CompareOverlayRenderTypes.outline(xrayEnabled));
        for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : visibleSurfaceBlocks) {
            DiffBlockEntry entry = surfaceBlock.entry();
            ColorChannels color = ColorChannels.of(entry.changeType());
            ShapeRenderer.renderShape(
                    matrices,
                    lineConsumer,
                    Shapes.block(),
                    entry.pos().x() - camera.x,
                    entry.pos().y() - camera.y,
                    entry.pos().z() - camera.z,
                    color.argb(),
                    OUTLINE_WIDTH
            );
        }
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
        return CompareOverlaySpatialIndex.build(changedBlocks).selectNearestEntries(cameraX, cameraY, cameraZ);
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
        private final Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition;
        private final CompareOverlaySpatialIndex spatialIndex;
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
                    buildSurfaceBlocksByPosition(changedBlocks),
                    debugEnabled,
                    visible
            );
        }

        private OverlayState(
                String projectName,
                String leftVersionId,
                String rightVersionId,
                List<DiffBlockEntry> changedBlocks,
                Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition,
                CompareOverlaySpatialIndex spatialIndex,
                boolean debugEnabled,
                boolean visible
        ) {
            this.projectName = projectName == null ? "" : projectName;
            this.leftVersionId = leftVersionId;
            this.rightVersionId = rightVersionId;
            this.changedBlocks = List.copyOf(changedBlocks);
            this.surfaceBlocksByPosition = surfaceBlocksByPosition;
            this.spatialIndex = spatialIndex;
            this.debugEnabled = debugEnabled;
            this.visible = visible;
        }

        private OverlayState(
                String projectName,
                String leftVersionId,
                String rightVersionId,
                List<DiffBlockEntry> changedBlocks,
                Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition,
                boolean debugEnabled,
                boolean visible
        ) {
            this(
                    projectName,
                    leftVersionId,
                    rightVersionId,
                    changedBlocks,
                    surfaceBlocksByPosition,
                    CompareOverlaySpatialIndex.build(surfaceBlocksByPosition.values().stream()
                            .map(CompareOverlaySurfaceResolver.SurfaceBlock::entry)
                            .toList()),
                    debugEnabled,
                    visible
            );
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
                    this.surfaceBlocksByPosition,
                    this.spatialIndex,
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

        private int surfaceBlockCount() {
            return this.surfaceBlocksByPosition.size();
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
            List<DiffBlockEntry> nearestEntries = this.spatialIndex.selectNearestEntries(cameraX, cameraY, cameraZ);
            List<CompareOverlaySurfaceResolver.SurfaceBlock> nearestSurfaceBlocks = new ArrayList<>(nearestEntries.size());
            for (DiffBlockEntry entry : nearestEntries) {
                CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock = this.surfaceBlocksByPosition.get(pack(entry.pos()));
                if (surfaceBlock != null) {
                    nearestSurfaceBlocks.add(surfaceBlock);
                }
            }
            this.cachedVisibleSurfaceBlocks = List.copyOf(nearestSurfaceBlocks);
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

        private static Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> buildSurfaceBlocksByPosition(
                List<DiffBlockEntry> changedBlocks
        ) {
            Set<Long> changedBlockPositions = SURFACE_RESOLVER.indexPositions(changedBlocks);
            List<CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocks = SURFACE_RESOLVER.resolve(
                    changedBlocks,
                    changedBlockPositions
            );
            Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> indexed = new LinkedHashMap<>();
            for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : surfaceBlocks) {
                indexed.put(pack(surfaceBlock.entry().pos()), surfaceBlock);
            }
            return Map.copyOf(indexed);
        }

        private static long pack(io.github.luma.domain.model.BlockPoint pos) {
            return BlockPos.asLong(pos.x(), pos.y(), pos.z());
        }
    }

    private static final class CompareOverlaySpatialIndex {

        private static final CompareOverlaySpatialIndex EMPTY = new CompareOverlaySpatialIndex(List.of());

        private final List<ChunkBucket> buckets;

        private CompareOverlaySpatialIndex(List<ChunkBucket> buckets) {
            this.buckets = buckets;
        }

        private static CompareOverlaySpatialIndex build(List<DiffBlockEntry> changedBlocks) {
            if (changedBlocks == null || changedBlocks.isEmpty()) {
                return EMPTY;
            }

            Map<Long, ChunkBucketBuilder> builders = new LinkedHashMap<>();
            for (DiffBlockEntry entry : changedBlocks) {
                int chunkX = Math.floorDiv(entry.pos().x(), 16);
                int chunkZ = Math.floorDiv(entry.pos().z(), 16);
                builders.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ChunkBucketBuilder(chunkX, chunkZ))
                        .add(entry);
            }

            List<ChunkBucket> buckets = new ArrayList<>(builders.size());
            for (ChunkBucketBuilder builder : builders.values()) {
                buckets.add(builder.freeze());
            }
            return new CompareOverlaySpatialIndex(List.copyOf(buckets));
        }

        private List<DiffBlockEntry> selectNearestEntries(double cameraX, double cameraY, double cameraZ) {
            if (this.buckets.isEmpty()) {
                return List.of();
            }

            List<RankedBucket> rankedBuckets = new ArrayList<>(this.buckets.size());
            for (ChunkBucket bucket : this.buckets) {
                rankedBuckets.add(new RankedBucket(bucket, bucket.minDistanceSquared(cameraX, cameraY, cameraZ)));
            }
            rankedBuckets.sort(Comparator.comparingDouble(RankedBucket::distanceSquared));

            PriorityQueue<RankedEntry> selected = new PriorityQueue<>(
                    MAX_RENDERED_BLOCKS,
                    Comparator.comparingDouble(RankedEntry::distanceSquared).reversed()
            );
            for (RankedBucket rankedBucket : rankedBuckets) {
                RankedEntry farthest = selected.peek();
                if (selected.size() >= MAX_RENDERED_BLOCKS
                        && farthest != null
                        && rankedBucket.distanceSquared() > farthest.distanceSquared()) {
                    break;
                }

                for (DiffBlockEntry entry : rankedBucket.bucket().entries()) {
                    addNearest(selected, entry, cameraX, cameraY, cameraZ);
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

        private static void addNearest(
                PriorityQueue<RankedEntry> selected,
                DiffBlockEntry entry,
                double cameraX,
                double cameraY,
                double cameraZ
        ) {
            double distanceSquared = distanceSquared(entry, cameraX, cameraY, cameraZ);
            if (selected.size() < MAX_RENDERED_BLOCKS) {
                selected.add(new RankedEntry(entry, distanceSquared));
                return;
            }

            RankedEntry farthest = selected.peek();
            if (farthest != null && distanceSquared < farthest.distanceSquared()) {
                selected.poll();
                selected.add(new RankedEntry(entry, distanceSquared));
            }
        }

        private static long chunkKey(int chunkX, int chunkZ) {
            return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
        }
    }

    private static final class ChunkBucketBuilder {

        private final int chunkX;
        private final int chunkZ;
        private final List<DiffBlockEntry> entries = new ArrayList<>();
        private int minY = Integer.MAX_VALUE;
        private int maxY = Integer.MIN_VALUE;

        private ChunkBucketBuilder(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        private void add(DiffBlockEntry entry) {
            this.entries.add(entry);
            this.minY = Math.min(this.minY, entry.pos().y());
            this.maxY = Math.max(this.maxY, entry.pos().y());
        }

        private ChunkBucket freeze() {
            return new ChunkBucket(this.chunkX, this.chunkZ, this.minY, this.maxY, List.copyOf(this.entries));
        }
    }

    private record ChunkBucket(int chunkX, int chunkZ, int minY, int maxY, List<DiffBlockEntry> entries) {

        private double minDistanceSquared(double cameraX, double cameraY, double cameraZ) {
            double nearestX = clamp(cameraX, this.chunkX << 4, (this.chunkX << 4) + 16.0D);
            double nearestY = clamp(cameraY, this.minY, this.maxY + 1.0D);
            double nearestZ = clamp(cameraZ, this.chunkZ << 4, (this.chunkZ << 4) + 16.0D);
            double dx = nearestX - cameraX;
            double dy = nearestY - cameraY;
            double dz = nearestZ - cameraZ;
            return (dx * dx) + (dy * dy) + (dz * dz);
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private record RankedBucket(ChunkBucket bucket, double distanceSquared) {
    }
}
