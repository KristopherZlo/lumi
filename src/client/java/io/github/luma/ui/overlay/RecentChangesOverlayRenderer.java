package io.github.luma.ui.overlay;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.UndoRedoAction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;

public final class RecentChangesOverlayRenderer {

    private static final int MAX_RECENT_RENDERED_BLOCKS = 512;
    private static final int MAX_RECENT_VOLUME_BOXES = 96;
    private static final int AGGREGATE_THRESHOLD = 512;
    private static final int DENSE_BLOB_THRESHOLD = 4096;
    private static final int MAX_ACTIONS = 10;
    private static final int BASE_ALPHA = 136;
    private static final int ALPHA_STEP = 12;
    private static final float FILL_ALPHA_SCALE = 0.38F;
    private static final int MIN_FILL_ALPHA = 24;
    private static final float OUTLINE_WIDTH = 2.75F;
    private static final float AGGREGATE_OUTLINE_WIDTH = 1.5F;
    private static final float FACE_OUTSET = 0.003F;
    private static final int AGGREGATE_FILL_ALPHA = 18;
    private static final int DENSE_BLOB_FILL_ALPHA = 28;
    private static final CompareOverlaySurfaceResolver SURFACE_RESOLVER = new CompareOverlaySurfaceResolver();
    private static final RecentChangesOverlayVolumeMerger VOLUME_MERGER = new RecentChangesOverlayVolumeMerger();
    private static final AtomicReference<OverlayState> ACTIVE_STATE = new AtomicReference<>(null);

    private RecentChangesOverlayRenderer() {
    }

    public static void show(String projectId, List<UndoRedoAction> actions) {
        show(projectId, actions, LumaDebugLog.globalEnabled(), RecentChangesOverlayCoordinator.PreviewTarget.UNDO);
    }

    public static void show(
            String projectId,
            List<UndoRedoAction> actions,
            boolean debugEnabled,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        activate(prepare(projectId, actions, debugEnabled, previewTarget, -1L));
    }

    static PreparedOverlay prepare(
            String projectId,
            List<UndoRedoAction> actions,
            boolean debugEnabled,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
            long revision
    ) {
        List<RecentChangeEntry> entries = flatten(actions);
        if (entries.isEmpty()) {
            return new PreparedOverlay(
                    projectId,
                    revision,
                    previewTarget,
                    actionCount(actions),
                    0,
                    0,
                    0,
                    debugEnabled,
                    null
            );
        }

        OverlayState state = new OverlayState(projectId, revision, previewTarget, entries, debugEnabled);
        return new PreparedOverlay(
                projectId,
                revision,
                previewTarget,
                actionCount(actions),
                entries.size(),
                state.surfaceEntryCount(),
                state.volumeBoxCount(),
                debugEnabled,
                state
        );
    }

    static void activate(PreparedOverlay prepared) {
        if (prepared == null) {
            ACTIVE_STATE.set(null);
            return;
        }
        OverlayState state = prepared.state();
        ACTIVE_STATE.set(state);
        OverlayDiagnostics.getInstance().log(
                prepared.debugEnabled(),
                "recent-show",
                "recent-overlay",
                "Loaded recent overlay project={} preview={} revision={} actions={} entries={} surfaceEntries={} volumeBoxes={}",
                prepared.projectId(),
                prepared.previewTarget(),
                prepared.revision(),
                prepared.actionCount(),
                prepared.entryCount(),
                prepared.surfaceEntryCount(),
                prepared.volumeBoxCount()
        );
    }

    public static void clear() {
        ACTIVE_STATE.set(null);
    }

    public static boolean visible() {
        return ACTIVE_STATE.get() != null;
    }

    static boolean visibleFor(String projectId, long revision, RecentChangesOverlayCoordinator.PreviewTarget previewTarget) {
        OverlayState state = ACTIVE_STATE.get();
        return state != null && state.matches(projectId, revision, previewTarget);
    }

    static int visibleSurfaceEntryCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.visibleSelection(cameraX, cameraY, cameraZ).surfaceEntries().size();
    }

    static int visibleAggregateBoxCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.visibleSelection(cameraX, cameraY, cameraZ).volumeBoxes().size();
    }

    public static void render(WorldRenderContext context) {
        OverlayState state = ACTIVE_STATE.get();
        if (state == null) {
            OverlayDiagnostics.getInstance().log(
                    false,
                    "recent-skip-no-state",
                    "recent-overlay",
                    "Render skipped reason={} entries={} surfaceEntries={}",
                    "no-state",
                    0,
                    0
            );
            return;
        }
        if (state.entries().isEmpty()) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "recent-skip-empty",
                    "recent-overlay",
                    "Render skipped reason={} entries={} surfaceEntries={}",
                    "empty",
                    0,
                    state.surfaceEntryCount()
            );
            return;
        }
        try {
            renderOverlay(context, state);
        } catch (RuntimeException exception) {
            OverlayDiagnostics.getInstance().logNow(
                    state.debugEnabled(),
                    "recent-overlay",
                    "Render failed with {}: {}",
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
            ACTIVE_STATE.compareAndSet(state, null);
            LumaMod.LOGGER.warn("Disabled recent changes overlay after a render pipeline failure", exception);
        }
    }

    private static void renderOverlay(WorldRenderContext context, OverlayState state) {
        if (context == null) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "recent-skip-null-context",
                    "recent-overlay",
                    "Render skipped reason={} entries={} surfaceEntries={}",
                    "null-context",
                    state.entries().size(),
                    state.surfaceEntryCount()
            );
            return;
        }
        var matrices = context.matrices();
        if (matrices == null) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "recent-skip-null-matrices",
                    "recent-overlay",
                    "Render skipped reason={} entries={} surfaceEntries={}",
                    "null-matrices",
                    state.entries().size(),
                    state.surfaceEntryCount()
            );
            return;
        }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        VisibleSelection selection = state.visibleSelection(camera.x, camera.y, camera.z);
        List<SurfaceEntry> visibleSurfaceEntries = selection.surfaceEntries();
        List<RecentChangesOverlayVolumeMerger.OverlayBox> volumeBoxes = selection.volumeBoxes();
        OverlayDiagnostics.getInstance().log(
                state.debugEnabled(),
                "recent-frame",
                "recent-overlay",
                "Render frame entries={} surfaceEntries={} renderedEntries={} volumeBoxes={} denseBlob={} camera={}:{}:{}",
                state.entries().size(),
                state.surfaceEntryCount(),
                visibleSurfaceEntries.size(),
                volumeBoxes.size(),
                state.denseBlob(),
                camera.x,
                camera.y,
                camera.z
        );

        var fillType = CompareOverlayRenderTypes.fill(false);
        var fillBuffer = OverlayImmediateRenderer.begin(fillType);
        VertexConsumer fillConsumer = fillBuffer;
        int filledFaceCount = 0;
        int minFillAlpha = Integer.MAX_VALUE;
        int maxFillAlpha = Integer.MIN_VALUE;
        for (SurfaceEntry surfaceEntry : visibleSurfaceEntries) {
            RecentChangeEntry entry = surfaceEntry.entry();
            float minX = (float) (entry.pos().x() - camera.x) - FACE_OUTSET;
            float minY = (float) (entry.pos().y() - camera.y) - FACE_OUTSET;
            float minZ = (float) (entry.pos().z() - camera.z) - FACE_OUTSET;
            float maxX = (float) (entry.pos().x() + 1.0D - camera.x) + FACE_OUTSET;
            float maxY = (float) (entry.pos().y() + 1.0D - camera.y) + FACE_OUTSET;
            float maxZ = (float) (entry.pos().z() + 1.0D - camera.z) + FACE_OUTSET;
            int fillAlpha = Math.max(MIN_FILL_ALPHA, Math.round(entry.alpha() * FILL_ALPHA_SCALE));
            minFillAlpha = Math.min(minFillAlpha, fillAlpha);
            maxFillAlpha = Math.max(maxFillAlpha, fillAlpha);
            filledFaceCount += OverlayFaceRenderer.renderFilledBox(
                    matrices,
                    fillConsumer,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    surfaceEntry.surfaceBlock(),
                    0xFF,
                    0x9C,
                    0x3A,
                    fillAlpha
            );
        }
        if (visibleSurfaceEntries.isEmpty()) {
            minFillAlpha = 0;
            maxFillAlpha = 0;
        }
        int volumeFillAlpha = state.denseBlob() ? DENSE_BLOB_FILL_ALPHA : AGGREGATE_FILL_ALPHA;
        for (RecentChangesOverlayVolumeMerger.OverlayBox box : volumeBoxes) {
            filledFaceCount += OverlayFaceRenderer.renderSolidBox(
                    matrices,
                    fillConsumer,
                    (float) (box.minX() - camera.x) - FACE_OUTSET,
                    (float) (box.minY() - camera.y) - FACE_OUTSET,
                    (float) (box.minZ() - camera.z) - FACE_OUTSET,
                    (float) (box.maxX() - camera.x) + FACE_OUTSET,
                    (float) (box.maxY() - camera.y) + FACE_OUTSET,
                    (float) (box.maxZ() - camera.z) + FACE_OUTSET,
                    0xFF,
                    0x9C,
                    0x3A,
                    volumeFillAlpha
            );
        }
        boolean fillDrawn = OverlayImmediateRenderer.draw(fillType, fillBuffer);

        OverlayDiagnostics.getInstance().log(
                state.debugEnabled(),
                "recent-fill-pass",
                "recent-overlay",
                "Fill pass entries={} volumeBoxes={} faces={} vertices={} alphaRange={}..{} drawn={} renderType={} consumer={} outset={}",
                visibleSurfaceEntries.size(),
                volumeBoxes.size(),
                filledFaceCount,
                filledFaceCount * 4,
                minFillAlpha,
                maxFillAlpha,
                fillDrawn,
                fillType,
                fillConsumer.getClass().getName(),
                FACE_OUTSET
        );

        var outlineType = CompareOverlayRenderTypes.outline(false);
        var lineBuffer = OverlayImmediateRenderer.begin(outlineType);
        VertexConsumer lineConsumer = lineBuffer;
        for (SurfaceEntry surfaceEntry : visibleSurfaceEntries) {
            RecentChangeEntry entry = surfaceEntry.entry();
            ShapeRenderer.renderShape(
                    matrices,
                    lineConsumer,
                    Shapes.block(),
                    entry.pos().x() - camera.x,
                    entry.pos().y() - camera.y,
                    entry.pos().z() - camera.z,
                    0xFFFF9C3A,
                    OUTLINE_WIDTH);
        }
        int volumeOutlineColor = state.denseBlob() ? 0xB3FF9C3A : 0x99FF9C3A;
        for (RecentChangesOverlayVolumeMerger.OverlayBox box : volumeBoxes) {
            ShapeRenderer.renderShape(
                    matrices,
                    lineConsumer,
                    Shapes.create(new AABB(0.0D, 0.0D, 0.0D, box.maxX() - box.minX(), box.maxY() - box.minY(), box.maxZ() - box.minZ())),
                    box.minX() - camera.x,
                    box.minY() - camera.y,
                    box.minZ() - camera.z,
                    volumeOutlineColor,
                    AGGREGATE_OUTLINE_WIDTH);
        }
        OverlayImmediateRenderer.draw(outlineType, lineBuffer);
    }

    private static List<RecentChangeEntry> flatten(List<UndoRedoAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<Long, RecentChangeEntry> flattened = new LinkedHashMap<>();
        int actionIndex = 0;
        for (UndoRedoAction action : actions) {
            if (action == null || action.isEmpty()) {
                continue;
            }
            int alpha = Math.max(ALPHA_STEP, BASE_ALPHA - (actionIndex * ALPHA_STEP));
            for (var change : action.redoChanges()) {
                long key = net.minecraft.core.BlockPos.asLong(change.pos().x(), change.pos().y(), change.pos().z());
                flattened.putIfAbsent(key, new RecentChangeEntry(change.pos(), alpha));
            }
            actionIndex += 1;
            if (actionIndex >= MAX_ACTIONS) {
                break;
            }
        }
        return List.copyOf(flattened.values());
    }

    private static int actionCount(List<UndoRedoAction> actions) {
        return actions == null ? 0 : actions.size();
    }

    private static List<SurfaceEntry> selectNearestSurfaceEntries(
            List<SurfaceEntry> entries,
            double cameraX,
            double cameraY,
            double cameraZ) {
        if (entries.isEmpty()) {
            return List.of();
        }

        PriorityQueue<RankedEntry> selected = new PriorityQueue<>(
                MAX_RECENT_RENDERED_BLOCKS,
                Comparator.comparingDouble(RankedEntry::distanceSquared).reversed());
        for (SurfaceEntry entry : entries) {
            double distanceSquared = distanceSquared(entry.entry(), cameraX, cameraY, cameraZ);
            if (selected.size() < MAX_RECENT_RENDERED_BLOCKS) {
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
        List<SurfaceEntry> result = new ArrayList<>(ranked.size());
        for (RankedEntry entry : ranked) {
            result.add(entry.entry());
        }
        return List.copyOf(result);
    }

    private static List<RecentChangesOverlayVolumeMerger.OverlayBox> selectNearestAggregateBoxes(
            List<SurfaceEntry> surfaceEntries,
            List<SurfaceEntry> selectedEntries,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        if (surfaceEntries.size() <= AGGREGATE_THRESHOLD || selectedEntries.size() >= surfaceEntries.size()) {
            return List.of();
        }

        java.util.HashSet<Long> selectedPositions = new java.util.HashSet<>((selectedEntries.size() * 4 / 3) + 1);
        for (SurfaceEntry entry : selectedEntries) {
            selectedPositions.add(pack(entry.entry().pos()));
        }

        Map<AggregateKey, RecentChangesOverlayVolumeMerger.OverlayBox> boxes = new LinkedHashMap<>();
        for (SurfaceEntry entry : surfaceEntries) {
            if (selectedPositions.contains(pack(entry.entry().pos()))) {
                continue;
            }
            AggregateKey key = AggregateKey.from(entry.entry().pos());
            boxes.putIfAbsent(key, key.toBox());
        }

        PriorityQueue<RankedAggregateBox> selected = new PriorityQueue<>(
                MAX_RECENT_VOLUME_BOXES,
                Comparator.comparingDouble(RankedAggregateBox::distanceSquared).reversed());
        for (RecentChangesOverlayVolumeMerger.OverlayBox box : boxes.values()) {
            double distanceSquared = box.distanceSquared(cameraX, cameraY, cameraZ);
            if (selected.size() < MAX_RECENT_VOLUME_BOXES) {
                selected.add(new RankedAggregateBox(box, distanceSquared));
                continue;
            }

            RankedAggregateBox farthest = selected.peek();
            if (farthest != null && distanceSquared < farthest.distanceSquared()) {
                selected.poll();
                selected.add(new RankedAggregateBox(box, distanceSquared));
            }
        }

        List<RankedAggregateBox> ranked = new ArrayList<>(selected);
        ranked.sort(Comparator.comparingDouble(RankedAggregateBox::distanceSquared));
        List<RecentChangesOverlayVolumeMerger.OverlayBox> result = new ArrayList<>(ranked.size());
        for (RankedAggregateBox entry : ranked) {
            result.add(entry.box());
        }
        return List.copyOf(result);
    }

    private static List<RecentChangesOverlayVolumeMerger.OverlayBox> selectNearestVolumeBoxes(
            List<RecentChangesOverlayVolumeMerger.OverlayBox> boxes,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        if (boxes.isEmpty()) {
            return List.of();
        }
        if (boxes.size() <= MAX_RECENT_VOLUME_BOXES) {
            return boxes;
        }

        PriorityQueue<RankedAggregateBox> selected = new PriorityQueue<>(
                MAX_RECENT_VOLUME_BOXES,
                Comparator.comparingDouble(RankedAggregateBox::distanceSquared).reversed());
        for (RecentChangesOverlayVolumeMerger.OverlayBox box : boxes) {
            double distanceSquared = box.distanceSquared(cameraX, cameraY, cameraZ);
            if (selected.size() < MAX_RECENT_VOLUME_BOXES) {
                selected.add(new RankedAggregateBox(box, distanceSquared));
                continue;
            }

            RankedAggregateBox farthest = selected.peek();
            if (farthest != null && distanceSquared < farthest.distanceSquared()) {
                selected.poll();
                selected.add(new RankedAggregateBox(box, distanceSquared));
            }
        }

        List<RankedAggregateBox> ranked = new ArrayList<>(selected);
        ranked.sort(Comparator.comparingDouble(RankedAggregateBox::distanceSquared));
        List<RecentChangesOverlayVolumeMerger.OverlayBox> result = new ArrayList<>(ranked.size());
        for (RankedAggregateBox entry : ranked) {
            result.add(entry.box());
        }
        return List.copyOf(result);
    }

    private static double distanceSquared(RecentChangeEntry entry, double cameraX, double cameraY, double cameraZ) {
        double dx = (entry.pos().x() + 0.5D) - cameraX;
        double dy = (entry.pos().y() + 0.5D) - cameraY;
        double dz = (entry.pos().z() + 0.5D) - cameraZ;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private record RankedEntry(SurfaceEntry entry, double distanceSquared) {
    }

    private record RankedAggregateBox(RecentChangesOverlayVolumeMerger.OverlayBox box, double distanceSquared) {
    }

    private record RecentChangeEntry(BlockPoint pos, int alpha) {
    }

    private record SurfaceEntry(
            RecentChangeEntry entry,
            CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock) {
    }

    private record VisibleSelection(
            List<SurfaceEntry> surfaceEntries,
            List<RecentChangesOverlayVolumeMerger.OverlayBox> volumeBoxes) {
    }

    record PreparedOverlay(
            String projectId,
            long revision,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
            int actionCount,
            int entryCount,
            int surfaceEntryCount,
            int volumeBoxCount,
            boolean debugEnabled,
            OverlayState state) {
    }

    private record AggregateKey(int chunkX, int sectionY, int chunkZ) {

        private static AggregateKey from(BlockPoint pos) {
            return new AggregateKey(Math.floorDiv(pos.x(), 16), Math.floorDiv(pos.y(), 16), Math.floorDiv(pos.z(), 16));
        }

        private RecentChangesOverlayVolumeMerger.OverlayBox toBox() {
            return new RecentChangesOverlayVolumeMerger.OverlayBox(
                    this.chunkX << 4,
                    this.sectionY << 4,
                    this.chunkZ << 4,
                    (this.chunkX + 1) << 4,
                    (this.sectionY + 1) << 4,
                    (this.chunkZ + 1) << 4
            );
        }
    }

    private static final class OverlayState {

        private final String projectId;
        private final long revision;
        private final RecentChangesOverlayCoordinator.PreviewTarget previewTarget;
        private final List<RecentChangeEntry> entries;
        private final List<SurfaceEntry> surfaceEntries;
        private final List<RecentChangesOverlayVolumeMerger.OverlayBox> volumeBoxes;
        private final boolean debugEnabled;
        private final boolean denseBlob;
        private int cachedCameraBlockX = Integer.MIN_VALUE;
        private int cachedCameraBlockY = Integer.MIN_VALUE;
        private int cachedCameraBlockZ = Integer.MIN_VALUE;
        private VisibleSelection cachedVisibleSelection = new VisibleSelection(List.of(), List.of());

        private OverlayState(
                String projectId,
                long revision,
                RecentChangesOverlayCoordinator.PreviewTarget previewTarget,
                List<RecentChangeEntry> entries,
                boolean debugEnabled
        ) {
            this.projectId = projectId;
            this.revision = revision;
            this.previewTarget = previewTarget == null
                    ? RecentChangesOverlayCoordinator.PreviewTarget.UNDO
                    : previewTarget;
            this.entries = List.copyOf(entries);
            this.debugEnabled = debugEnabled;
            this.denseBlob = this.entries.size() > DENSE_BLOB_THRESHOLD;
            if (this.denseBlob) {
                this.surfaceEntries = List.of();
                this.volumeBoxes = VOLUME_MERGER.merge(this.entries.stream()
                        .map(RecentChangeEntry::pos)
                        .toList());
            } else {
                this.surfaceEntries = this.buildSurfaceEntries(this.entries);
                this.volumeBoxes = List.of();
            }
        }

        private List<RecentChangeEntry> entries() {
            return this.entries;
        }

        private boolean debugEnabled() {
            return this.debugEnabled;
        }

        private boolean denseBlob() {
            return this.denseBlob;
        }

        private boolean matches(
                String projectId,
                long revision,
                RecentChangesOverlayCoordinator.PreviewTarget previewTarget
        ) {
            return java.util.Objects.equals(this.projectId, projectId)
                    && this.revision == revision
                    && this.previewTarget == (previewTarget == null
                    ? RecentChangesOverlayCoordinator.PreviewTarget.UNDO
                    : previewTarget);
        }

        private int surfaceEntryCount() {
            return this.surfaceEntries.size();
        }

        private int volumeBoxCount() {
            return this.volumeBoxes.size();
        }

        private synchronized VisibleSelection visibleSelection(double cameraX, double cameraY, double cameraZ) {
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
            List<SurfaceEntry> visibleEntries = selectNearestSurfaceEntries(this.surfaceEntries, cameraX, cameraY, cameraZ);
            this.cachedVisibleSelection = new VisibleSelection(
                    visibleEntries,
                    this.denseBlob
                            ? selectNearestVolumeBoxes(this.volumeBoxes, cameraX, cameraY, cameraZ)
                            : selectNearestAggregateBoxes(this.surfaceEntries, visibleEntries, cameraX, cameraY, cameraZ)
            );
            return this.cachedVisibleSelection;
        }

        private List<SurfaceEntry> buildSurfaceEntries(List<RecentChangeEntry> entries) {
            List<io.github.luma.domain.model.DiffBlockEntry> diffEntries = entries.stream()
                    .map(entry -> new io.github.luma.domain.model.DiffBlockEntry(
                            entry.pos(),
                            "",
                            "",
                            io.github.luma.domain.model.ChangeType.CHANGED))
                    .toList();
            Set<Long> occupiedPositions = SURFACE_RESOLVER.indexPositions(diffEntries);
            Map<Long, RecentChangeEntry> entriesByPosition = new LinkedHashMap<>();
            for (RecentChangeEntry entry : entries) {
                entriesByPosition.put(BlockPos.asLong(entry.pos().x(), entry.pos().y(), entry.pos().z()), entry);
            }
            List<CompareOverlaySurfaceResolver.SurfaceBlock> surfaceBlocks = SURFACE_RESOLVER.resolve(
                    diffEntries,
                    occupiedPositions
            );
            List<SurfaceEntry> resolved = new ArrayList<>(surfaceBlocks.size());
            for (CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock : surfaceBlocks) {
                RecentChangeEntry entry = entriesByPosition.get(BlockPos.asLong(
                        surfaceBlock.entry().pos().x(),
                        surfaceBlock.entry().pos().y(),
                        surfaceBlock.entry().pos().z()
                ));
                if (entry != null) {
                    resolved.add(new SurfaceEntry(entry, surfaceBlock));
                }
            }
            return List.copyOf(resolved);
        }
    }

    private static long pack(BlockPoint pos) {
        return BlockPos.asLong(pos.x(), pos.y(), pos.z());
    }
}
