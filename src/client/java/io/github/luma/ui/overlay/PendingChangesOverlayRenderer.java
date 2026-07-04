package io.github.luma.ui.overlay;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.telemetry.TelemetryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Renders cumulative unsaved project changes while the configured action key is held.
 */
public final class PendingChangesOverlayRenderer {

    private static final int MAX_SECTION_UPLOADS_PER_FRAME = 12;
    private static final int DETAILED_DIFF_RENDER_LIMIT = CompareOverlayRenderer.DETAILED_DIFF_RENDER_LIMIT;
    private static final int FILL_ALPHA = 52;
    private static final int DENSE_FILL_ALPHA = 36;
    private static final float OUTLINE_WIDTH = 2.75F;
    private static final float DENSE_OUTLINE_WIDTH = 1.5F;
    private static final float FACE_OUTSET = 0.003F;
    private static final int ORANGE_RED = 0xFF;
    private static final int ORANGE_GREEN = 0x9C;
    private static final int ORANGE_BLUE = 0x3A;
    private static final int ORANGE_OUTLINE = 0xFFFF9C3A;
    private static final CompareOverlaySurfaceResolver SURFACE_RESOLVER = new CompareOverlaySurfaceResolver();
    private static final OverlayVolumeMerger VOLUME_MERGER = new OverlayVolumeMerger();
    private static final AtomicReference<OverlayState> ACTIVE_STATE = new AtomicReference<>(null);

    private PendingChangesOverlayRenderer() {
    }

    static PreparedOverlay prepare(PendingChangesOverlaySnapshot snapshot, boolean debugEnabled) {
        PendingChangesOverlaySnapshot resolved = snapshot == null
                ? PendingChangesOverlaySnapshot.empty("")
                : snapshot;
        if (resolved.blockChanges().isEmpty()) {
            return new PreparedOverlay(
                    resolved.projectId(),
                    resolved.revision(),
                    0,
                    0,
                    0,
                    0,
                    debugEnabled,
                    null
            );
        }

        OverlayState state = new OverlayState(resolved, debugEnabled);
        return new PreparedOverlay(
                resolved.projectId(),
                resolved.revision(),
                resolved.blockChanges().size(),
                resolved.entityChangeCount(),
                state.surfaceEntryCount(),
                state.volumeBoxCount(),
                debugEnabled,
                state
        );
    }

    static void activate(PreparedOverlay prepared) {
        if (prepared == null || prepared.state() == null) {
            clear();
            return;
        }
        OverlayState state = prepared.state();
        closePrevious(ACTIVE_STATE.getAndSet(state));
        OverlayDiagnostics.getInstance().log(
                prepared.debugEnabled(),
                "pending-show",
                "pending-overlay",
                "Loaded pending overlay project={} revision={} blocks={} entities={} surfaceEntries={} volumeBoxes={} meshSections={}",
                prepared.projectId(),
                prepared.revision(),
                prepared.blockChangeCount(),
                prepared.entityChangeCount(),
                prepared.surfaceEntryCount(),
                prepared.volumeBoxCount(),
                state.meshSectionCount()
        );
    }

    public static void clear() {
        closePrevious(ACTIVE_STATE.getAndSet(null));
    }

    public static boolean visible() {
        return ACTIVE_STATE.get() != null;
    }

    static boolean visibleFor(String projectId, long revision) {
        OverlayState state = ACTIVE_STATE.get();
        return state != null && state.matches(projectId, revision);
    }

    static int visibleSurfaceEntryCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.surfaceEntryCount();
    }

    static int visibleAggregateBoxCountForTest(double cameraX, double cameraY, double cameraZ) {
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
            return;
        }
        if (state.entryCount() == 0) {
            return;
        }
        try {
            renderOverlay(context, state);
        } catch (RuntimeException exception) {
            OverlayDiagnostics.getInstance().logNow(
                    state.debugEnabled(),
                    "pending-overlay",
                    "Render failed with {}: {}",
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            if (ACTIVE_STATE.compareAndSet(state, null)) {
                state.close();
            }
            TelemetryService.getInstance().recordRenderOverlayDisabled("pending", exception);
            LumaMod.LOGGER.warn("Disabled pending changes overlay after a render pipeline failure", exception);
        }
    }

    private static void closePrevious(OverlayState state) {
        if (state != null) {
            state.close();
        }
    }

    private static void renderOverlay(WorldRenderContext context, OverlayState state) {
        if (context == null || context.matrices() == null) {
            return;
        }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        OverlayMeshBatch.RenderStats renderStats = state.meshBatch().render(
                CompareOverlayRenderTypes.fill(false),
                CompareOverlayRenderTypes.outline(false),
                camera,
                renderDistanceChunks(),
                MAX_SECTION_UPLOADS_PER_FRAME
        );
        OverlayDiagnostics.getInstance().log(
                state.debugEnabled(),
                "pending-frame",
                "pending-overlay",
                "Render frame entries={} surfaceEntries={} volumeBoxes={} meshSections={}/{} uploaded={} skipped={} denseBlob={} camera={}:{}:{}",
                state.entryCount(),
                state.surfaceEntryCount(),
                state.volumeBoxCount(),
                renderStats.filledSections(),
                renderStats.totalSections(),
                renderStats.uploadedSections(),
                renderStats.skippedSections(),
                state.denseBlob(),
                camera.x,
                camera.y,
                camera.z
        );
    }

    private static int renderDistanceChunks() {
        Minecraft client = Minecraft.getInstance();
        return client == null || client.options == null ? 8 : client.options.getEffectiveRenderDistance();
    }

    private static List<DiffBlockEntry> toDiffEntries(List<StoredBlockChange> changes) {
        List<DiffBlockEntry> entries = new ArrayList<>(changes.size());
        for (StoredBlockChange change : changes) {
            entries.add(new DiffBlockEntry(
                    change.pos(),
                    change.oldValue() == null ? "" : change.oldValue().toStateSnbt(),
                    change.newValue() == null ? "" : change.newValue().toStateSnbt(),
                    changeType(change),
                    change.oldValue() == null ? "minecraft:air" : change.oldValue().blockId(),
                    change.newValue() == null ? "minecraft:air" : change.newValue().blockId()
            ));
        }
        return List.copyOf(entries);
    }

    private static ChangeType changeType(StoredBlockChange change) {
        boolean oldAir = isAir(change.oldValue() == null ? null : change.oldValue().blockId());
        boolean newAir = isAir(change.newValue() == null ? null : change.newValue().blockId());
        if (oldAir && !newAir) {
            return ChangeType.ADDED;
        }
        if (!oldAir && newAir) {
            return ChangeType.REMOVED;
        }
        return ChangeType.CHANGED;
    }

    private static boolean isAir(String blockId) {
        return blockId == null || blockId.isBlank() || "minecraft:air".equals(blockId);
    }

    record PreparedOverlay(
            String projectId,
            long revision,
            int blockChangeCount,
            int entityChangeCount,
            int surfaceEntryCount,
            int volumeBoxCount,
            boolean debugEnabled,
            OverlayState state
    ) {
    }

    private record VolumeBox(OverlayVolumeMerger.OverlayBox box) {
    }

    private static final class OverlayState {

        private final String projectId;
        private final long revision;
        private final int entryCount;
        private final Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition;
        private final List<VolumeBox> volumeBoxes;
        private final OverlayMeshBatch meshBatch;
        private final boolean denseBlob;
        private final boolean debugEnabled;

        private OverlayState(PendingChangesOverlaySnapshot snapshot, boolean debugEnabled) {
            this.projectId = snapshot.projectId();
            this.revision = snapshot.revision();
            this.entryCount = snapshot.blockChanges().size();
            OverlayGeometry geometry = buildGeometry(snapshot.blockChanges());
            this.surfaceBlocksByPosition = geometry.surfaceBlocksByPosition();
            this.volumeBoxes = geometry.volumeBoxes();
            this.meshBatch = this.buildMeshBatch();
            this.denseBlob = geometry.denseBlob();
            this.debugEnabled = debugEnabled;
        }

        private boolean matches(String projectId, long revision) {
            return java.util.Objects.equals(this.projectId, projectId == null ? "" : projectId)
                    && this.revision == revision;
        }

        private int entryCount() {
            return this.entryCount;
        }

        private boolean debugEnabled() {
            return this.debugEnabled;
        }

        private boolean denseBlob() {
            return this.denseBlob;
        }

        private int surfaceEntryCount() {
            return this.surfaceBlocksByPosition.size();
        }

        private int volumeBoxCount() {
            return this.volumeBoxes.size();
        }

        private int meshSectionCount() {
            return this.meshBatch.sectionCount();
        }

        private int meshPrimitiveCount() {
            return this.meshBatch.primitiveCountForTest();
        }

        private int visibleMeshSectionCount(
                double cameraX,
                double cameraY,
                double cameraZ,
                int renderDistanceChunks
        ) {
            return this.meshBatch.visibleSectionCountForTest(
                    cameraX,
                    cameraY,
                    cameraZ,
                    renderDistanceChunks
            );
        }

        private OverlayMeshBatch meshBatch() {
            return this.meshBatch;
        }

        private void close() {
            this.meshBatch.close();
        }

        private OverlayMeshBatch buildMeshBatch() {
            OverlayMeshBatch.Builder builder = OverlayMeshBatch.builder();
            builder.addMergedSurfaceBlocks(
                    new ArrayList<>(this.surfaceBlocksByPosition.values()),
                    new OverlayMeshBatch.SurfaceStyle(
                        ORANGE_RED,
                        ORANGE_GREEN,
                        ORANGE_BLUE,
                        FILL_ALPHA,
                        ORANGE_OUTLINE,
                        OUTLINE_WIDTH,
                        FACE_OUTSET
                    )
            );
            for (VolumeBox volumeBox : this.volumeBoxes) {
                OverlayVolumeMerger.OverlayBox box = volumeBox.box();
                builder.addBox(
                        box.minX(),
                        box.minY(),
                        box.minZ(),
                        box.maxX(),
                        box.maxY(),
                        box.maxZ(),
                        ORANGE_RED,
                        ORANGE_GREEN,
                        ORANGE_BLUE,
                        DENSE_FILL_ALPHA,
                        ORANGE_OUTLINE,
                        DENSE_OUTLINE_WIDTH,
                        FACE_OUTSET,
                        0.0F
                );
            }
            return builder.build();
        }

        private static OverlayGeometry buildGeometry(List<StoredBlockChange> changes) {
            if (changes == null || changes.isEmpty()) {
                return OverlayGeometry.EMPTY;
            }
            if (changes.size() > DETAILED_DIFF_RENDER_LIMIT) {
                List<BlockPoint> positions = changes.stream()
                        .map(StoredBlockChange::pos)
                        .toList();
                List<VolumeBox> boxes = VOLUME_MERGER.merge(positions).stream()
                        .map(VolumeBox::new)
                        .toList();
                return new OverlayGeometry(Map.of(), boxes, true);
            }

            List<DiffBlockEntry> diffEntries = toDiffEntries(changes);
            Set<Long> occupiedPositions = SURFACE_RESOLVER.indexPositions(diffEntries);
            List<CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocks = SURFACE_RESOLVER.resolve(
                    diffEntries,
                    occupiedPositions
            );
            Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> indexed = new LinkedHashMap<>();
            for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : surfaceBlocks) {
                indexed.put(BlockPos.asLong(
                        surfaceBlock.entry().pos().x(),
                        surfaceBlock.entry().pos().y(),
                        surfaceBlock.entry().pos().z()
                ), surfaceBlock);
            }
            return new OverlayGeometry(Map.copyOf(indexed), List.of(), false);
        }

        private record OverlayGeometry(
                Map<Long, CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocksByPosition,
                List<VolumeBox> volumeBoxes,
                boolean denseBlob
        ) {

            private static final OverlayGeometry EMPTY = new OverlayGeometry(
                    Map.of(),
                    List.of(),
                    false
            );
        }
    }
}
