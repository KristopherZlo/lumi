package io.github.luma.ui.overlay;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class CompareOverlayRenderer {

    private static final String CURRENT_WORLD_REFERENCE = "current";
    static final int DETAILED_DIFF_RENDER_LIMIT = 50_000;
    private static final int MAX_SECTION_UPLOADS_PER_FRAME = 64;
    private static final float NORMAL_FILL_ALPHA = 48.0F;
    private static final float XRAY_FILL_ALPHA = 96.0F;
    private static final float DENSE_NORMAL_FILL_ALPHA = 32.0F;
    private static final float DENSE_XRAY_FILL_ALPHA = 72.0F;
    private static final float OUTLINE_WIDTH = 2.75F;
    private static final float DENSE_OUTLINE_WIDTH = 1.5F;
    private static final float FACE_OUTSET = 0.003F;
    private static final float DENSE_FACE_OUTSET = 0.02F;
    private static final float DENSE_OUTLINE_OUTSET = 0.02F;
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
        activate(prepare(projectName, leftVersionId, rightVersionId, changedBlocks, debugEnabled, true));
    }

    static PreparedOverlay prepare(
            String projectName,
            String leftVersionId,
            String rightVersionId,
            List<DiffBlockEntry> changedBlocks,
            boolean debugEnabled,
            boolean visible
    ) {
        boolean resolvedDebug = debugEnabled || LumaDebugLog.globalEnabled();
        List<DiffBlockEntry> resolvedBlocks = changedBlocks == null ? List.of() : changedBlocks;
        OverlayState state = new OverlayState(projectName, leftVersionId, rightVersionId, resolvedBlocks, resolvedDebug, visible);
        return new PreparedOverlay(
                projectName == null ? "" : projectName,
                leftVersionId,
                rightVersionId,
                resolvedBlocks.size(),
                state.surfaceBlockCount(),
                state.volumeBoxCount(),
                state.meshSectionCount(),
                state.denseBlob(),
                resolvedDebug,
                visible,
                state
        );
    }

    static void activate(PreparedOverlay prepared) {
        if (prepared == null) {
            clear();
            return;
        }
        OverlayState state = prepared.state();
        closePrevious(ACTIVE_STATE.getAndSet(state));
        OverlayDiagnostics.getInstance().logNow(
                prepared.debugEnabled(),
                "compare-overlay",
                "Activated compare overlay {} -> {} with changedBlocks={} surfaceBlocks={} volumeBoxes={} meshSections={} denseBlob={}",
                prepared.leftVersionId(),
                prepared.rightVersionId(),
                prepared.changedBlockCount(),
                prepared.surfaceBlockCount(),
                prepared.volumeBoxCount(),
                prepared.meshSectionCount(),
                prepared.denseBlob()
        );
    }

    static void discard(PreparedOverlay prepared) {
        if (prepared != null && prepared.state() != null) {
            prepared.state().close();
        }
    }

    public static void clear() {
        OverlayState state = ACTIVE_STATE.getAndSet(null);
        if (state != null && (state.debugEnabled() || LumaDebugLog.globalEnabled())) {
            LumaDebugLog.log(
                    "compare-overlay",
                    "Cleared compare overlay {} -> {}",
                    state.leftVersionId(),
                    state.rightVersionId()
            );
        }
        closePrevious(state);
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

    public static boolean shouldPrepareInBackground(List<DiffBlockEntry> changedBlocks) {
        return changedBlocks != null && changedBlocks.size() > DETAILED_DIFF_RENDER_LIMIT;
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
        closePrevious(ACTIVE_STATE.getAndSet(replacement));
        OverlayDiagnostics.getInstance().log(
                resolvedDebug,
                "compare-refresh",
                "compare-overlay",
                "Refreshed compare overlay {} -> {} with changedBlocks={} surfaceBlocks={} volumeBoxes={} meshSections={} denseBlob={} visible={}",
                leftVersionId,
                rightVersionId,
                replacement.changedBlockCount(),
                replacement.surfaceBlockCount(),
                replacement.volumeBoxCount(),
                replacement.meshSectionCount(),
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
        return state == null ? 0 : state.surfaceBlockCount();
    }

    static int visibleVolumeBoxCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.volumeBoxCount();
    }

    static int meshSectionCountForTest() {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.meshSectionCount();
    }

    static int meshPrimitiveCountForTest() {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.meshPrimitiveCount();
    }

    static int visibleMeshSectionCountForTest(
            double cameraX,
            double cameraY,
            double cameraZ,
            int renderDistanceChunks
    ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null
                ? 0
                : state.visibleMeshSectionCount(cameraX, cameraY, cameraZ, renderDistanceChunks);
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

    private static void closePrevious(OverlayState state) {
        if (state != null) {
            state.close();
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
        if (context.matrices() == null) {
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
        OverlayMeshBatch.RenderStats renderStats = state.meshBatch(xrayEnabled).render(
                CompareOverlayRenderTypes.fill(xrayEnabled),
                CompareOverlayRenderTypes.outline(xrayEnabled),
                camera,
                renderDistanceChunks(),
                MAX_SECTION_UPLOADS_PER_FRAME
        );
        OverlayDiagnostics.getInstance().log(
                state.debugEnabled(),
                "compare-frame",
                "compare-overlay",
                "Render frame changedBlocks={} surfaceBlocks={} volumeBoxes={} meshSections={}/{} uploaded={} skipped={} denseBlob={} xray={} camera={}:{}:{}",
                state.changedBlockCount(),
                state.surfaceBlockCount(),
                state.volumeBoxCount(),
                renderStats.filledSections(),
                renderStats.totalSections(),
                renderStats.uploadedSections(),
                renderStats.skippedSections(),
                state.denseBlob(),
                xrayEnabled,
                camera.x,
                camera.y,
                camera.z
        );
    }

    private static int renderDistanceChunks() {
        Minecraft client = Minecraft.getInstance();
        return client == null || client.options == null ? 8 : client.options.getEffectiveRenderDistance();
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

    private record VolumeBox(OverlayVolumeMerger.OverlayBox box, ChangeType changeType) {
    }

    record PreparedOverlay(
            String projectName,
            String leftVersionId,
            String rightVersionId,
            int changedBlockCount,
            int surfaceBlockCount,
            int volumeBoxCount,
            int meshSectionCount,
            boolean denseBlob,
            boolean debugEnabled,
            boolean visible,
            OverlayState state
    ) {
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
        private final List<VolumeBox> volumeBoxes;
        private final OverlayMeshBatch normalMeshBatch;
        private final OverlayMeshBatch xrayMeshBatch;
        private final boolean denseBlob;
        private final boolean debugEnabled;
        private final boolean visible;

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
            this.volumeBoxes = geometry.volumeBoxes();
            this.normalMeshBatch = buildMeshBatch(this.surfaceBlocksByPosition.values().stream().toList(), this.volumeBoxes, false);
            this.xrayMeshBatch = buildMeshBatch(this.surfaceBlocksByPosition.values().stream().toList(), this.volumeBoxes, true);
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
                List<VolumeBox> volumeBoxes,
                OverlayMeshBatch normalMeshBatch,
                OverlayMeshBatch xrayMeshBatch,
                boolean denseBlob,
                boolean debugEnabled,
                boolean visible
        ) {
            this.projectName = projectName == null ? "" : projectName;
            this.leftVersionId = leftVersionId;
            this.rightVersionId = rightVersionId;
            this.changedBlockCount = changedBlockCount;
            this.surfaceBlocksByPosition = surfaceBlocksByPosition;
            this.volumeBoxes = volumeBoxes;
            this.normalMeshBatch = normalMeshBatch;
            this.xrayMeshBatch = xrayMeshBatch;
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
                    this.volumeBoxes,
                    this.normalMeshBatch,
                    this.xrayMeshBatch,
                    this.denseBlob,
                    this.debugEnabled,
                    nextVisible
            );
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

        private int meshSectionCount() {
            return this.normalMeshBatch.sectionCount();
        }

        private int meshPrimitiveCount() {
            return this.normalMeshBatch.primitiveCountForTest();
        }

        private int visibleMeshSectionCount(
                double cameraX,
                double cameraY,
                double cameraZ,
                int renderDistanceChunks
        ) {
            return this.normalMeshBatch.visibleSectionCountForTest(
                    cameraX,
                    cameraY,
                    cameraZ,
                    renderDistanceChunks
            );
        }

        private OverlayMeshBatch meshBatch(boolean xrayEnabled) {
            return xrayEnabled ? this.xrayMeshBatch : this.normalMeshBatch;
        }

        private boolean denseBlob() {
            return this.denseBlob;
        }

        private void close() {
            this.normalMeshBatch.close();
            this.xrayMeshBatch.close();
        }

        private static OverlayGeometry buildGeometry(List<DiffBlockEntry> changedBlocks) {
            if (changedBlocks.isEmpty()) {
                return OverlayGeometry.EMPTY;
            }
            if (changedBlocks.size() > DETAILED_DIFF_RENDER_LIMIT) {
                return new OverlayGeometry(
                        Map.of(),
                        buildVolumeBoxes(changedBlocks),
                        true
                );
            }

            Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition =
                    buildSurfaceBlocksByPosition(changedBlocks);
            return new OverlayGeometry(
                    surfaceBlocksByPosition,
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

        private static OverlayMeshBatch buildMeshBatch(
                List<CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocks,
                List<VolumeBox> volumeBoxes,
                boolean xrayEnabled
        ) {
            OverlayMeshBatch.Builder builder = OverlayMeshBatch.builder();
            int fillAlpha = Math.round(xrayEnabled ? XRAY_FILL_ALPHA : NORMAL_FILL_ALPHA);
            int denseFillAlpha = Math.round(xrayEnabled ? DENSE_XRAY_FILL_ALPHA : DENSE_NORMAL_FILL_ALPHA);
            for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : surfaceBlocks) {
                ColorChannels color = ColorChannels.of(surfaceBlock.entry().changeType());
                builder.addSurfaceBlock(
                        surfaceBlock,
                        color.red(),
                        color.green(),
                        color.blue(),
                        fillAlpha,
                        color.argb(),
                        OUTLINE_WIDTH,
                        FACE_OUTSET
                );
            }
            for (VolumeBox volumeBox : volumeBoxes) {
                OverlayVolumeMerger.OverlayBox box = volumeBox.box();
                ColorChannels color = ColorChannels.of(volumeBox.changeType());
                builder.addBox(
                        box.minX(),
                        box.minY(),
                        box.minZ(),
                        box.maxX(),
                        box.maxY(),
                        box.maxZ(),
                        color.red(),
                        color.green(),
                        color.blue(),
                        denseFillAlpha,
                        color.argb(0xB3),
                        DENSE_OUTLINE_WIDTH,
                        DENSE_FACE_OUTSET,
                        DENSE_OUTLINE_OUTSET
                );
            }
            return builder.build();
        }

        private static ChangeType normalizedType(ChangeType type) {
            return type == null ? ChangeType.CHANGED : type;
        }

        private static long pack(io.github.luma.domain.model.BlockPoint pos) {
            return BlockPos.asLong(pos.x(), pos.y(), pos.z());
        }

        private record OverlayGeometry(
                Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition,
                List<VolumeBox> volumeBoxes,
                boolean denseBlob) {

            private static final OverlayGeometry EMPTY = new OverlayGeometry(
                    Map.of(),
                    List.of(),
                    false
            );
        }
    }

}
