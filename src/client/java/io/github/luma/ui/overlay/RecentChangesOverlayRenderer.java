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
import net.minecraft.world.phys.shapes.Shapes;

public final class RecentChangesOverlayRenderer {

    private static final int MAX_RENDERED_BLOCKS = 2048;
    private static final int MAX_ACTIONS = 10;
    private static final int BASE_ALPHA = 136;
    private static final int ALPHA_STEP = 12;
    private static final float FILL_ALPHA_SCALE = 0.62F;
    private static final int MIN_FILL_ALPHA = 36;
    private static final float OUTLINE_WIDTH = 2.75F;
    private static final float FACE_OUTSET = 0.003F;
    private static final CompareOverlaySurfaceResolver SURFACE_RESOLVER = new CompareOverlaySurfaceResolver();
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
        List<RecentChangeEntry> entries = flatten(actions);
        if (entries.isEmpty()) {
            ACTIVE_STATE.set(null);
            OverlayDiagnostics.getInstance().log(
                    debugEnabled,
                    "recent-show",
                    "recent-overlay",
                    "Loaded recent overlay project={} preview={} actions={} entries={} surfaceEntries={}",
                    projectId,
                    previewTarget,
                    actionCount(actions),
                    0,
                    0
            );
            return;
        }

        OverlayState state = new OverlayState(projectId, entries, debugEnabled);
        ACTIVE_STATE.set(state);
        OverlayDiagnostics.getInstance().log(
                debugEnabled,
                "recent-show",
                "recent-overlay",
                "Loaded recent overlay project={} preview={} actions={} entries={} surfaceEntries={}",
                projectId,
                previewTarget,
                actionCount(actions),
                entries.size(),
                state.surfaceEntryCount()
        );
    }

    public static void clear() {
        ACTIVE_STATE.set(null);
    }

    public static boolean visible() {
        return ACTIVE_STATE.get() != null;
    }

    static int visibleSurfaceEntryCountForTest(double cameraX, double cameraY, double cameraZ) {
        OverlayState state = ACTIVE_STATE.get();
        return state == null ? 0 : state.visibleSurfaceEntries(cameraX, cameraY, cameraZ).size();
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
        var consumers = context.consumers();
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
        if (consumers == null) {
            OverlayDiagnostics.getInstance().log(
                    state.debugEnabled(),
                    "recent-skip-null-consumers",
                    "recent-overlay",
                    "Render skipped reason={} entries={} surfaceEntries={}",
                    "null-consumers",
                    state.entries().size(),
                    state.surfaceEntryCount()
            );
            return;
        }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        List<SurfaceEntry> visibleSurfaceEntries = state.visibleSurfaceEntries(camera.x, camera.y, camera.z);
        OverlayDiagnostics.getInstance().log(
                state.debugEnabled(),
                "recent-frame",
                "recent-overlay",
                "Render frame entries={} surfaceEntries={} renderedEntries={} camera={}:{}:{}",
                state.entries().size(),
                state.surfaceEntryCount(),
                visibleSurfaceEntries.size(),
                camera.x,
                camera.y,
                camera.z
        );

        VertexConsumer fillConsumer = consumers.getBuffer(CompareOverlayRenderTypes.fill(false));
        for (SurfaceEntry surfaceEntry : visibleSurfaceEntries) {
            RecentChangeEntry entry = surfaceEntry.entry();
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
                    surfaceEntry.surfaceBlock(),
                    0xFF,
                    0x9C,
                    0x3A,
                    Math.max(MIN_FILL_ALPHA, Math.round(entry.alpha() * FILL_ALPHA_SCALE))
            );
        }

        VertexConsumer lineConsumer = consumers.getBuffer(CompareOverlayRenderTypes.outline(false));
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
                MAX_RENDERED_BLOCKS,
                Comparator.comparingDouble(RankedEntry::distanceSquared).reversed());
        for (SurfaceEntry entry : entries) {
            double distanceSquared = distanceSquared(entry.entry(), cameraX, cameraY, cameraZ);
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
        List<SurfaceEntry> result = new ArrayList<>(ranked.size());
        for (RankedEntry entry : ranked) {
            result.add(entry.entry());
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

    private record RecentChangeEntry(BlockPoint pos, int alpha) {
    }

    private record SurfaceEntry(
            RecentChangeEntry entry,
            CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock) {
    }

    private static final class OverlayState {

        private final String projectId;
        private final List<RecentChangeEntry> entries;
        private final List<SurfaceEntry> surfaceEntries;
        private final boolean debugEnabled;
        private int cachedCameraBlockX = Integer.MIN_VALUE;
        private int cachedCameraBlockY = Integer.MIN_VALUE;
        private int cachedCameraBlockZ = Integer.MIN_VALUE;
        private List<SurfaceEntry> cachedVisibleEntries = List.of();

        private OverlayState(String projectId, List<RecentChangeEntry> entries, boolean debugEnabled) {
            this.projectId = projectId;
            this.entries = List.copyOf(entries);
            this.surfaceEntries = this.buildSurfaceEntries(this.entries);
            this.debugEnabled = debugEnabled;
        }

        private List<RecentChangeEntry> entries() {
            return this.entries;
        }

        private boolean debugEnabled() {
            return this.debugEnabled;
        }

        private int surfaceEntryCount() {
            return this.surfaceEntries.size();
        }

        private synchronized List<SurfaceEntry> visibleSurfaceEntries(double cameraX, double cameraY, double cameraZ) {
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
            this.cachedVisibleEntries = selectNearestSurfaceEntries(this.surfaceEntries, cameraX, cameraY, cameraZ);
            return this.cachedVisibleEntries;
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
}
