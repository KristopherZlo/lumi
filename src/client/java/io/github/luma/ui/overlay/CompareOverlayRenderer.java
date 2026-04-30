package io.github.luma.ui.overlay;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;

public final class CompareOverlayRenderer {

    private static final String CURRENT_WORLD_REFERENCE = "current";
    private static final int MAX_RENDERED_BLOCKS = 2048;
    private static final int DENSE_BLOB_THRESHOLD = 4096;
    private static final int MAX_RENDERED_VOLUME_BOXES = 128;
    private static final float NORMAL_FILL_ALPHA = 48.0F;
    private static final float XRAY_FILL_ALPHA = 96.0F;
    private static final float DENSE_NORMAL_FILL_ALPHA = 32.0F;
    private static final float DENSE_XRAY_FILL_ALPHA = 72.0F;
    private static final float OUTLINE_WIDTH = 2.75F;
    private static final float DENSE_OUTLINE_WIDTH = 1.5F;
    private static final float FACE_OUTSET = 0.003F;
    private static final CompareOverlaySurfaceResolver SURFACE_RESOLVER = new CompareOverlaySurfaceResolver();
    private static final OverlayVolumeMerger VOLUME_MERGER = new OverlayVolumeMerger();
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
        List<DiffBlockEntry> resolvedBlocks = changedBlocks == null ? List.of() : changedBlocks;
        OverlayState state = new OverlayState(projectName, leftVersionId, rightVersionId, resolvedBlocks, resolvedDebug, true);
        ACTIVE_STATE.set(state);
        OverlayDiagnostics.getInstance().logNow(
                resolvedDebug,
                "compare-overlay",
                "Activated compare overlay {} -> {} with changedBlocks={} surfaceBlocks={} volumeBoxes={} denseBlob={}",
                leftVersionId,
                rightVersionId,
                resolvedBlocks.size(),
                state.surfaceBlockCount(),
                state.volumeBoxCount(),
                state.denseBlob()
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

    public static boolean hasDataFor(String projectName, String leftVersionId, String rightVersionId) {
        OverlayState state = ACTIVE_STATE.get();
        return state != null && state.matches(projectName, leftVersionId, rightVersionId);
    }

    public static boolean visibleFor(String projectName, String leftVersionId, String rightVersionId) {
        OverlayState state = ACTIVE_STATE.get();
        return state != null && state.visible() && state.matches(projectName, leftVersionId, rightVersionId);
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
                        state.visible(),
                        state.changedBlockCount(),
                        state.denseBlob()
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
                changedBlocks == null ? List.of() : changedBlocks,
                resolvedDebug,
                current.visible()
        );
        ACTIVE_STATE.set(replacement);
        OverlayDiagnostics.getInstance().log(
                resolvedDebug,
                "compare-refresh",
                "compare-overlay",
                "Refreshed compare overlay {} -> {} with changedBlocks={} surfaceBlocks={} volumeBoxes={} denseBlob={} visible={}",
                leftVersionId,
                rightVersionId,
                replacement.changedBlockCount(),
                replacement.surfaceBlockCount(),
                replacement.volumeBoxCount(),
                replacement.denseBlob(),
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
        return state == null ? 0 : state.changedBlockCount();
    }

    static int visibleSurfaceBlockCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.visibleSurfaceBlocks(cameraX, cameraY, cameraZ).size();
    }

    static int visibleVolumeBoxCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.visibleSelection(cameraX, cameraY, cameraZ).volumeBoxes().size();
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
                    state.changedBlockCount(),
                    state.surfaceBlockCount(),
                    false,
                    XRAY_ENABLED.get()
            );
            return;
        }
        if (state.changedBlockCount() == 0) {
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
                    state.changedBlockCount(),
                    state.surfaceBlockCount(),
                    state.visible(),
                    xrayEnabled
            );
            return;
        }
        var matrices = context.matrices();
        if (matrices == null) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "compare-skip-null-matrices",
                    "compare-overlay",
                    "Render skipped reason={} changedBlocks={} surfaceBlocks={} visible={} xray={}",
                    "null-matrices",
                    state.changedBlockCount(),
                    state.surfaceBlockCount(),
                    state.visible(),
                    xrayEnabled
            );
            return;
        }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        VisibleSelection selection = state.visibleSelection(
                camera.x,
                camera.y,
                camera.z
        );
        List<CompareOverlaySurfaceResolver.SurfaceBlock> visibleSurfaceBlocks = selection.surfaceBlocks();
        List<VolumeBox> visibleVolumeBoxes = selection.volumeBoxes();
        OverlayDiagnostics.getInstance().log(
                state.debugEnabled(),
                "compare-frame",
                "compare-overlay",
                "Render frame changedBlocks={} surfaceBlocks={} renderedBlocks={} volumeBoxes={} denseBlob={} xray={} camera={}:{}:{}",
                state.changedBlockCount(),
                state.surfaceBlockCount(),
                visibleSurfaceBlocks.size(),
                visibleVolumeBoxes.size(),
                state.denseBlob(),
                xrayEnabled,
                camera.x,
                camera.y,
                camera.z
        );

        var fillType = CompareOverlayRenderTypes.fill(xrayEnabled);
        var fillBuffer = OverlayImmediateRenderer.begin(fillType);
        VertexConsumer fillConsumer = fillBuffer;
        int filledFaceCount = 0;
        int fillAlpha = Math.round(xrayEnabled ? XRAY_FILL_ALPHA : NORMAL_FILL_ALPHA);
        int denseFillAlpha = Math.round(xrayEnabled ? DENSE_XRAY_FILL_ALPHA : DENSE_NORMAL_FILL_ALPHA);
        for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : visibleSurfaceBlocks) {
            DiffBlockEntry entry = surfaceBlock.entry();
            ColorChannels color = ColorChannels.of(entry.changeType());
            float minX = (float) (entry.pos().x() - camera.x) - FACE_OUTSET;
            float minY = (float) (entry.pos().y() - camera.y) - FACE_OUTSET;
            float minZ = (float) (entry.pos().z() - camera.z) - FACE_OUTSET;
            float maxX = (float) (entry.pos().x() + 1.0D - camera.x) + FACE_OUTSET;
            float maxY = (float) (entry.pos().y() + 1.0D - camera.y) + FACE_OUTSET;
            float maxZ = (float) (entry.pos().z() + 1.0D - camera.z) + FACE_OUTSET;

            filledFaceCount += OverlayFaceRenderer.renderFilledBox(
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
                    fillAlpha
            );
        }
        for (VolumeBox volumeBox : visibleVolumeBoxes) {
            OverlayVolumeMerger.OverlayBox box = volumeBox.box();
            ColorChannels color = ColorChannels.of(volumeBox.changeType());
            filledFaceCount += OverlayFaceRenderer.renderSolidBox(
                    matrices,
                    fillConsumer,
                    (float) (box.minX() - camera.x) - FACE_OUTSET,
                    (float) (box.minY() - camera.y) - FACE_OUTSET,
                    (float) (box.minZ() - camera.z) - FACE_OUTSET,
                    (float) (box.maxX() - camera.x) + FACE_OUTSET,
                    (float) (box.maxY() - camera.y) + FACE_OUTSET,
                    (float) (box.maxZ() - camera.z) + FACE_OUTSET,
                    color.red(),
                    color.green(),
                    color.blue(),
                    denseFillAlpha
            );
        }
        boolean fillDrawn = OverlayImmediateRenderer.draw(fillType, fillBuffer);

        OverlayDiagnostics.getInstance().log(
                state.debugEnabled(),
                "compare-fill-pass",
                "compare-overlay",
                "Fill pass blocks={} volumeBoxes={} faces={} vertices={} alpha={} denseAlpha={} drawn={} renderType={} consumer={} xray={} outset={}",
                visibleSurfaceBlocks.size(),
                visibleVolumeBoxes.size(),
                filledFaceCount,
                filledFaceCount * 4,
                fillAlpha,
                denseFillAlpha,
                fillDrawn,
                fillType,
                fillConsumer.getClass().getName(),
                xrayEnabled,
                FACE_OUTSET
        );

        var outlineType = CompareOverlayRenderTypes.outline(xrayEnabled);
        var lineBuffer = OverlayImmediateRenderer.begin(outlineType);
        VertexConsumer lineConsumer = lineBuffer;
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
        for (VolumeBox volumeBox : visibleVolumeBoxes) {
            OverlayVolumeMerger.OverlayBox box = volumeBox.box();
            ColorChannels color = ColorChannels.of(volumeBox.changeType());
            ShapeRenderer.renderShape(
                    matrices,
                    lineConsumer,
                    Shapes.create(new AABB(
                            0.0D,
                            0.0D,
                            0.0D,
                            box.maxX() - box.minX(),
                            box.maxY() - box.minY(),
                            box.maxZ() - box.minZ()
                    )),
                    box.minX() - camera.x,
                    box.minY() - camera.y,
                    box.minZ() - camera.z,
                    color.argb(0xB3),
                    DENSE_OUTLINE_WIDTH
            );
        }
        OverlayImmediateRenderer.draw(outlineType, lineBuffer);
    }

    private record ColorChannels(int red, int green, int blue, int argb) {

        private static ColorChannels of(ChangeType type) {
            ChangeType normalized = type == null ? ChangeType.CHANGED : type;
            return switch (normalized) {
                case ADDED -> new ColorChannels(0x55, 0xFF, 0x55, 0xFF55FF55);
                case REMOVED -> new ColorChannels(0xFF, 0x55, 0x55, 0xFFFF5555);
                case CHANGED -> new ColorChannels(0xFF, 0xD4, 0x55, 0xFFFFD455);
            };
        }

        private int argb(int alpha) {
            return ((alpha & 0xFF) << 24) | ((this.red & 0xFF) << 16) | ((this.green & 0xFF) << 8) | (this.blue & 0xFF);
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

    private record RankedVolumeBox(VolumeBox volumeBox, double distanceSquared) {
    }

    private record VolumeBox(OverlayVolumeMerger.OverlayBox box, ChangeType changeType) {

        private double distanceSquared(double cameraX, double cameraY, double cameraZ) {
            return this.box.distanceSquared(cameraX, cameraY, cameraZ);
        }
    }

    private record VisibleSelection(
            List<CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocks,
            List<VolumeBox> volumeBoxes) {
    }

    public record RefreshRequest(
            String projectName,
            String leftVersionId,
            String rightVersionId,
            boolean debugEnabled,
            boolean visible,
            int changedBlockCount,
            boolean denseBlob
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
        private final int changedBlockCount;
        private final Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition;
        private final CompareOverlaySpatialIndex spatialIndex;
        private final List<VolumeBox> volumeBoxes;
        private final boolean denseBlob;
        private final boolean debugEnabled;
        private final boolean visible;
        private int cachedCameraBlockX = Integer.MIN_VALUE;
        private int cachedCameraBlockY = Integer.MIN_VALUE;
        private int cachedCameraBlockZ = Integer.MIN_VALUE;
        private VisibleSelection cachedVisibleSelection = new VisibleSelection(List.of(), List.of());

        private OverlayState(
                String projectName,
                String leftVersionId,
                String rightVersionId,
                List<DiffBlockEntry> changedBlocks,
                boolean debugEnabled,
                boolean visible
        ) {
            this.projectName = projectName == null ? "" : projectName;
            this.leftVersionId = leftVersionId;
            this.rightVersionId = rightVersionId;
            this.changedBlockCount = changedBlocks == null ? 0 : changedBlocks.size();
            OverlayGeometry geometry = buildGeometry(changedBlocks == null ? List.of() : changedBlocks);
            this.surfaceBlocksByPosition = geometry.surfaceBlocksByPosition();
            this.spatialIndex = geometry.spatialIndex();
            this.volumeBoxes = geometry.volumeBoxes();
            this.denseBlob = geometry.denseBlob();
            this.debugEnabled = debugEnabled;
            this.visible = visible;
        }

        private OverlayState(
                String projectName,
                String leftVersionId,
                String rightVersionId,
                int changedBlockCount,
                Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition,
                CompareOverlaySpatialIndex spatialIndex,
                List<VolumeBox> volumeBoxes,
                boolean denseBlob,
                boolean debugEnabled,
                boolean visible
        ) {
            this.projectName = projectName == null ? "" : projectName;
            this.leftVersionId = leftVersionId;
            this.rightVersionId = rightVersionId;
            this.changedBlockCount = changedBlockCount;
            this.surfaceBlocksByPosition = surfaceBlocksByPosition;
            this.spatialIndex = spatialIndex;
            this.volumeBoxes = volumeBoxes;
            this.denseBlob = denseBlob;
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

        private boolean matches(String projectName, String leftVersionId, String rightVersionId) {
            return this.projectName.equals(projectName == null ? "" : projectName)
                    && java.util.Objects.equals(this.leftVersionId, leftVersionId)
                    && java.util.Objects.equals(this.rightVersionId, rightVersionId);
        }

        private synchronized OverlayState withVisible(boolean nextVisible) {
            OverlayState replacement = new OverlayState(
                    this.projectName,
                    this.leftVersionId,
                    this.rightVersionId,
                    this.changedBlockCount,
                    this.surfaceBlocksByPosition,
                    this.spatialIndex,
                    this.volumeBoxes,
                    this.denseBlob,
                    this.debugEnabled,
                    nextVisible
            );
            replacement.cachedCameraBlockX = this.cachedCameraBlockX;
            replacement.cachedCameraBlockY = this.cachedCameraBlockY;
            replacement.cachedCameraBlockZ = this.cachedCameraBlockZ;
            replacement.cachedVisibleSelection = this.cachedVisibleSelection;
            return replacement;
        }

        private int changedBlockCount() {
            return this.changedBlockCount;
        }

        private int surfaceBlockCount() {
            return this.surfaceBlocksByPosition.size();
        }

        private int volumeBoxCount() {
            return this.volumeBoxes.size();
        }

        private boolean denseBlob() {
            return this.denseBlob;
        }

        private synchronized List<CompareOverlaySurfaceResolver.SurfaceBlock> visibleSurfaceBlocks(
                double cameraX,
                double cameraY,
                double cameraZ
        ) {
            return this.visibleSelection(cameraX, cameraY, cameraZ).surfaceBlocks();
        }

        private synchronized VisibleSelection visibleSelection(
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
                return this.cachedVisibleSelection;
            }

            this.cachedCameraBlockX = cameraBlockX;
            this.cachedCameraBlockY = cameraBlockY;
            this.cachedCameraBlockZ = cameraBlockZ;
            long startedAt = System.nanoTime();
            if (this.denseBlob) {
                this.cachedVisibleSelection = new VisibleSelection(
                        List.of(),
                        selectNearestVolumeBoxes(this.volumeBoxes, cameraX, cameraY, cameraZ)
                );
            } else {
                List<DiffBlockEntry> nearestEntries = this.spatialIndex.selectNearestEntries(cameraX, cameraY, cameraZ);
                List<CompareOverlaySurfaceResolver.SurfaceBlock> nearestSurfaceBlocks = new ArrayList<>(nearestEntries.size());
                for (DiffBlockEntry entry : nearestEntries) {
                    CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock = this.surfaceBlocksByPosition.get(pack(entry.pos()));
                    if (surfaceBlock != null) {
                        nearestSurfaceBlocks.add(surfaceBlock);
                    }
                }
                this.cachedVisibleSelection = new VisibleSelection(List.copyOf(nearestSurfaceBlocks), List.of());
            }
            if (this.debugEnabled) {
                LumaDebugLog.log(
                        "compare-overlay",
                        "Rebuilt visible overlay cache for {} -> {} at {}:{}:{}: total={} surface={} volume={} dense={} in {} us",
                        this.leftVersionId,
                        this.rightVersionId,
                        cameraBlockX,
                        cameraBlockY,
                        cameraBlockZ,
                        this.changedBlockCount,
                        this.cachedVisibleSelection.surfaceBlocks().size(),
                        this.cachedVisibleSelection.volumeBoxes().size(),
                        this.denseBlob,
                        (System.nanoTime() - startedAt) / 1_000L
                );
            }
            return this.cachedVisibleSelection;
        }

        private static OverlayGeometry buildGeometry(List<DiffBlockEntry> changedBlocks) {
            if (changedBlocks.isEmpty()) {
                return OverlayGeometry.EMPTY;
            }
            if (changedBlocks.size() > DENSE_BLOB_THRESHOLD) {
                return new OverlayGeometry(
                        Map.of(),
                        CompareOverlaySpatialIndex.EMPTY,
                        buildVolumeBoxes(changedBlocks),
                        true
                );
            }

            Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition =
                    buildSurfaceBlocksByPosition(changedBlocks);
            return new OverlayGeometry(
                    surfaceBlocksByPosition,
                    CompareOverlaySpatialIndex.build(surfaceBlocksByPosition.values().stream()
                            .map(CompareOverlaySurfaceResolver.SurfaceBlock::entry)
                            .toList()),
                    List.of(),
                    false
            );
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

        private static List<VolumeBox> buildVolumeBoxes(List<DiffBlockEntry> changedBlocks) {
            Map<ChangeType, List<BlockPoint>> positionsByType = new EnumMap<>(ChangeType.class);
            for (DiffBlockEntry entry : changedBlocks) {
                positionsByType.computeIfAbsent(normalizedType(entry.changeType()), ignored -> new ArrayList<>())
                        .add(entry.pos());
            }

            List<VolumeBox> volumeBoxes = new ArrayList<>();
            for (Map.Entry<ChangeType, List<BlockPoint>> entry : positionsByType.entrySet()) {
                for (OverlayVolumeMerger.OverlayBox box : VOLUME_MERGER.merge(entry.getValue())) {
                    volumeBoxes.add(new VolumeBox(box, entry.getKey()));
                }
            }
            return List.copyOf(volumeBoxes);
        }

        private static List<VolumeBox> selectNearestVolumeBoxes(
                List<VolumeBox> boxes,
                double cameraX,
                double cameraY,
                double cameraZ
        ) {
            if (boxes.isEmpty()) {
                return List.of();
            }
            if (boxes.size() <= MAX_RENDERED_VOLUME_BOXES) {
                return boxes;
            }

            PriorityQueue<RankedVolumeBox> selected = new PriorityQueue<>(
                    MAX_RENDERED_VOLUME_BOXES,
                    Comparator.comparingDouble(RankedVolumeBox::distanceSquared).reversed()
            );
            for (VolumeBox box : boxes) {
                double distanceSquared = box.distanceSquared(cameraX, cameraY, cameraZ);
                if (selected.size() < MAX_RENDERED_VOLUME_BOXES) {
                    selected.add(new RankedVolumeBox(box, distanceSquared));
                    continue;
                }

                RankedVolumeBox farthest = selected.peek();
                if (farthest != null && distanceSquared < farthest.distanceSquared()) {
                    selected.poll();
                    selected.add(new RankedVolumeBox(box, distanceSquared));
                }
            }

            List<RankedVolumeBox> ranked = new ArrayList<>(selected);
            ranked.sort(Comparator.comparingDouble(RankedVolumeBox::distanceSquared));
            List<VolumeBox> result = new ArrayList<>(ranked.size());
            for (RankedVolumeBox entry : ranked) {
                result.add(entry.volumeBox());
            }
            return List.copyOf(result);
        }

        private static ChangeType normalizedType(ChangeType type) {
            return type == null ? ChangeType.CHANGED : type;
        }

        private static long pack(io.github.luma.domain.model.BlockPoint pos) {
            return BlockPos.asLong(pos.x(), pos.y(), pos.z());
        }

        private record OverlayGeometry(
                Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition,
                CompareOverlaySpatialIndex spatialIndex,
                List<VolumeBox> volumeBoxes,
                boolean denseBlob) {

            private static final OverlayGeometry EMPTY = new OverlayGeometry(
                    Map.of(),
                    CompareOverlaySpatialIndex.EMPTY,
                    List.of(),
                    false
            );
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
